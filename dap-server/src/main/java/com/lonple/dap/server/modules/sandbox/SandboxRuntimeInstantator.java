package com.lonple.dap.server.modules.sandbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.sandbox.entity.SbRuntimeProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Profile 模板 → runtime_instance_json（不含占席/attach）。
 */
@Component
@RequiredArgsConstructor
public class SandboxRuntimeInstantator {

    private final ObjectMapper objectMapper;
    private final SandboxProperties props;

    @FunctionalInterface
    public interface PortSink {
        int allocate(String serviceKey, int poolStart, int poolEnd);
    }

    public record Instantiated(
            Map<String, Object> runtimeInstance,
            Map<String, String> env,
            List<Map<String, Object>> portPublishes,
            Map<String, Boolean> cacheMounts,
            String imageOverride,
            List<String> preferredResourceCodes,
            int leaseTtlHours,
            String integrationEnvCode) {
    }

    public Instantiated instantiate(
            SysProduct product,
            SbRuntimeProfile profile,
            String reqNo,
            String integrationEnvCodeOverride,
            Long tenantId,
            PortSink portAllocator) {
        JsonNode cfg;
        try {
            cfg = objectMapper.readTree(profile.getConfigJson());
        } catch (Exception e) {
            throw new BizException("Profile configJson 解析失败: " + e.getMessage());
        }
        String productCode = product.getCode() == null ? "product" : product.getCode();
        String req = StringUtils.hasText(reqNo) ? reqNo.trim() : "REQ";
        String reqNoSafe = req.replaceAll("[^A-Za-z0-9_]", "_");
        String tenant = tenantId == null ? "0" : String.valueOf(tenantId);

        String schemaPattern = text(cfg, "/database/schemaPattern", "it_{productCode}_{reqNoSafe}");
        String schema = render(schemaPattern, productCode, req, reqNoSafe, tenant, null, null);

        String redisPattern = text(cfg, "/middleware/redisKeyPrefixPattern", "{tenantId}:{productCode}:{reqNoSafe}:");
        String redisPrefix = render(redisPattern, productCode, req, reqNoSafe, tenant, schema, null);
        String mqPattern = text(cfg, "/middleware/mqPrefixPattern", "{productCode}.{reqNoSafe}.");
        String mqPrefix = render(mqPattern, productCode, req, reqNoSafe, tenant, schema, redisPrefix);

        String integration = StringUtils.hasText(integrationEnvCodeOverride)
                ? integrationEnvCodeOverride.trim()
                : text(cfg, "/integrationEnvDefault", "dev-integration");

        // 对接环境本期仅记录 code；地址合并待产品 Environment 就绪后补
        Map<String, String> integrationVars = Map.of(
                "dbHost", "127.0.0.1",
                "dbPort", "5432",
                "dbName", "postgres");

        int poolStart = cfg.path("ports").path("pool").path("start").asInt(props.getBusinessPortStart());
        int poolEnd = cfg.path("ports").path("pool").path("end").asInt(props.getBusinessPortEnd());

        Map<String, Object> ports = new LinkedHashMap<>();
        List<Map<String, Object>> publishes = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>();

        JsonNode services = cfg.path("ports").path("services");
        if (services.isArray()) {
            for (JsonNode svc : services) {
                String key = svc.path("key").asText(null);
                int containerPort = svc.path("containerPort").asInt(0);
                String envName = svc.path("env").asText(null);
                if (!StringUtils.hasText(key) || containerPort <= 0) {
                    continue;
                }
                int hostPort = portAllocator.allocate(key, poolStart, poolEnd);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("host", hostPort);
                p.put("container", containerPort);
                ports.put(key, p);
                Map<String, Object> pub = new LinkedHashMap<>();
                pub.put("host", hostPort);
                pub.put("container", containerPort);
                publishes.add(pub);
                if (StringUtils.hasText(envName)) {
                    env.put(envName, String.valueOf(containerPort));
                }
            }
        }

        String urlTemplate = text(cfg, "/database/urlTemplate", "");
        String jdbcUrl = render(urlTemplate, productCode, req, reqNoSafe, tenant, schema, redisPrefix)
                .replace("{dbHost}", integrationVars.get("dbHost"))
                .replace("{dbPort}", integrationVars.get("dbPort"))
                .replace("{dbName}", integrationVars.get("dbName"));

        JsonNode envTemplates = cfg.path("envTemplates");
        if (envTemplates.isArray()) {
            for (JsonNode t : envTemplates) {
                String name = t.path("name").asText(null);
                String value = t.path("value").asText("");
                if (StringUtils.hasText(name)) {
                    env.put(name, render(value, productCode, req, reqNoSafe, tenant, schema, redisPrefix));
                }
            }
        }
        // Profile 自由环境变量（跟 Profile 走；可覆盖同名模板键）
        applyProfileEnvVars(cfg, env, productCode, req, reqNoSafe, tenant, schema, redisPrefix);

        if (StringUtils.hasText(jdbcUrl)) {
            env.put("JDBC_URL", jdbcUrl);
        }
        env.putIfAbsent("DB_SCHEMA", schema);
        env.putIfAbsent("REDIS_PREFIX", redisPrefix);

        Map<String, Boolean> cache = new LinkedHashMap<>();
        cache.put("m2", cfg.path("cacheMounts").path("m2").asBoolean(true));
        cache.put("npm", cfg.path("cacheMounts").path("npm").asBoolean(true));

        String imageOverride = cfg.path("imageOverride").isNull() || cfg.path("imageOverride").asText().isBlank()
                ? null
                : cfg.path("imageOverride").asText();

        List<String> preferred = new ArrayList<>();
        JsonNode pref = cfg.path("sandbox").path("preferredResourceCodes");
        if (pref.isArray()) {
            pref.forEach(n -> {
                if (n.isTextual() && StringUtils.hasText(n.asText())) {
                    preferred.add(n.asText());
                }
            });
        }
        int ttl = cfg.path("sandbox").path("leaseTtlHours").asInt(props.getDefaultLeaseTtlHours());

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("profileCode", profile.getProfileCode());
        runtime.put("schema", schema);
        runtime.put("ports", ports);
        runtime.put("redisKeyPrefix", redisPrefix);
        runtime.put("mqPrefix", mqPrefix);
        runtime.put("integrationEnvCode", integration);
        runtime.put("integrationResolved", false);
        runtime.put("jdbcUrl", jdbcUrl);
        runtime.put("env", env);
        runtime.put("cacheMounts", cache);
        if (imageOverride != null) {
            runtime.put("imageOverride", imageOverride);
        }

        // fix port allocator: need poolEnd — re-allocate properly in lease service via wrapper
        return new Instantiated(runtime, env, publishes, cache, imageOverride, preferred, ttl, integration);
    }

    /** 仅读 Profile 里偏好资源码，不占端口（申请租约 insert 前选席用）。 */
    public List<String> preferredResourceCodes(SbRuntimeProfile profile) {
        if (profile == null || !StringUtils.hasText(profile.getConfigJson())) {
            return List.of();
        }
        try {
            JsonNode cfg = objectMapper.readTree(profile.getConfigJson());
            List<String> preferred = new ArrayList<>();
            JsonNode pref = cfg.path("sandbox").path("preferredResourceCodes");
            if (pref.isArray()) {
                pref.forEach(n -> {
                    if (n.isTextual() && StringUtils.hasText(n.asText())) {
                        preferred.add(n.asText());
                    }
                });
            }
            return preferred;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 预览：不占端口，用假端口。 */
    public Map<String, Object> preview(
            SysProduct product, SbRuntimeProfile profile, String reqNo, String integrationEnvCode, Long tenantId) {
        final int[] cursor = {props.getBusinessPortStart()};
        Instantiated inst = instantiate(product, profile, reqNo, integrationEnvCode, tenantId,
                (key, poolStart, poolEnd) -> cursor[0]++);
        return inst.runtimeInstance();
    }

    /**
     * 支持两种写法：
     * <pre>
     * "envVars": [ {"name":"USER_CENTER_URL","value":"https://...","enabled":true} ]
     * "env": { "USER_CENTER_URL": "https://..." }
     * </pre>
     */
    private static void applyProfileEnvVars(
            JsonNode cfg,
            Map<String, String> env,
            String productCode,
            String reqNo,
            String reqNoSafe,
            String tenantId,
            String schema,
            String redisPrefix) {
        JsonNode arr = cfg.path("envVars");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                if (t.path("enabled").isBoolean() && !t.path("enabled").asBoolean()) {
                    continue;
                }
                String name = t.path("name").asText(null);
                if (!StringUtils.hasText(name)) {
                    name = t.path("envName").asText(null);
                }
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String upper = name.trim().toUpperCase();
                if ("CODE_TOOLS_PORT".equals(upper) || "SANDBOX_TOKEN".equals(upper) || "WORKSPACE_ROOT".equals(upper)) {
                    continue;
                }
                String value = t.path("value").asText("");
                if (!t.has("value") && t.has("envValue")) {
                    value = t.path("envValue").asText("");
                }
                env.put(name.trim(), render(value, productCode, reqNo, reqNoSafe, tenantId, schema, redisPrefix));
            }
        }
        JsonNode obj = cfg.path("env");
        if (obj.isObject()) {
            obj.properties().forEach(e -> {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    return;
                }
                String upper = e.getKey().trim().toUpperCase();
                if ("CODE_TOOLS_PORT".equals(upper) || "SANDBOX_TOKEN".equals(upper) || "WORKSPACE_ROOT".equals(upper)) {
                    return;
                }
                String value = e.getValue() == null || e.getValue().isNull() ? "" : e.getValue().asText("");
                env.put(e.getKey().trim(), render(value, productCode, reqNo, reqNoSafe, tenantId, schema, redisPrefix));
            });
        }
    }

    private static String text(JsonNode root, String pointer, String def) {
        JsonNode n = root.at(pointer);
        if (n == null || n.isMissingNode() || n.isNull() || !n.isValueNode()) {
            return def;
        }
        String v = n.asText();
        return StringUtils.hasText(v) ? v : def;
    }

    private static String render(
            String template,
            String productCode,
            String reqNo,
            String reqNoSafe,
            String tenantId,
            String schema,
            String redisPrefix) {
        if (template == null) {
            return "";
        }
        String s = template
                .replace("{productCode}", productCode == null ? "" : productCode)
                .replace("{reqNo}", reqNo == null ? "" : reqNo)
                .replace("{reqNoSafe}", reqNoSafe == null ? "" : reqNoSafe)
                .replace("{tenantId}", tenantId == null ? "" : tenantId);
        if (schema != null) {
            s = s.replace("{schema}", schema);
        }
        if (redisPrefix != null) {
            s = s.replace("{redisKeyPrefix}", redisPrefix);
        }
        return s;
    }
}
