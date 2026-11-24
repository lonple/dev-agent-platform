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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profile 模板 → runtime_instance_json（不含占席/attach）。
 *
 * <p>平台只认通用输入（端口清单、数据库连接、执行命令、测试开关），
 * 不替产品生成任何专用变量名：产品实际读取的变量由 Profile 的 envTemplates / envVars
 * 以 {@code {占位符}} 形式自行声明。</p>
 */
@Component
@RequiredArgsConstructor
public class SandboxRuntimeInstantator {

    private final ObjectMapper objectMapper;
    private final SandboxProperties props;

    /** 变量值占位符，如 {productCode}、{database.host}、{ports.api}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)\\}");

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

        // 通用输入：数据库连接（只作为占位符取值，不生成变量名）
        JsonNode db = cfg.path("database");
        String dbHost = value(db, "host");
        String dbPort = value(db, "port");
        String dbName = value(db, "name");
        String dbUser = value(db, "username");
        String dbPassword = value(db, "password");
        String explicitSchema = value(db, "schema");

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("productCode", productCode);
        placeholders.put("reqNo", req);
        placeholders.put("reqNoSafe", reqNoSafe);
        placeholders.put("tenantId", tenant);

        String schemaPattern = text(cfg, "/database/schemaPattern", "it_{productCode}_{reqNoSafe}");
        String schema = explicitSchema != null ? explicitSchema : render(schemaPattern, placeholders);
        placeholders.put("schema", schema);

        placeholders.put("database.host", nz(dbHost));
        placeholders.put("database.port", nz(dbPort));
        placeholders.put("database.name", nz(dbName));
        placeholders.put("database.username", nz(dbUser));
        placeholders.put("database.password", nz(dbPassword));
        placeholders.put("database.schema", schema);
        // 兼容旧文档里的短写
        placeholders.put("dbHost", nz(dbHost));
        placeholders.put("dbPort", nz(dbPort));
        placeholders.put("dbName", nz(dbName));

        String redisPattern = text(cfg, "/middleware/redisKeyPrefixPattern", "{tenantId}:{productCode}:{reqNoSafe}:");
        String redisPrefix = render(redisPattern, placeholders);
        placeholders.put("redisKeyPrefix", redisPrefix);
        String mqPattern = text(cfg, "/middleware/mqPrefixPattern", "{productCode}.{reqNoSafe}.");
        String mqPrefix = render(mqPattern, placeholders);
        placeholders.put("mqPrefix", mqPrefix);

        String integration = StringUtils.hasText(integrationEnvCodeOverride)
                ? integrationEnvCodeOverride.trim()
                : text(cfg, "/integrationEnvDefault", "dev-integration");

        int poolStart = cfg.path("ports").path("pool").path("start").asInt(props.getBusinessPortStart());
        int poolEnd = cfg.path("ports").path("pool").path("end").asInt(props.getBusinessPortEnd());

        Map<String, Object> ports = new LinkedHashMap<>();
        List<Map<String, Object>> publishes = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>();
        Map<String, Integer> servicePorts = new LinkedHashMap<>();

        for (Map<String, Object> svc : effectiveServices(cfg)) {
            String key = (String) svc.get("key");
            int containerPort = (Integer) svc.get("containerPort");
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
            servicePorts.put(key, containerPort);
            placeholders.put("ports." + key, String.valueOf(containerPort));
            placeholders.put("ports." + key + ".host", String.valueOf(hostPort));
        }

        JsonNode envTemplates = cfg.path("envTemplates");
        if (envTemplates.isArray()) {
            for (JsonNode t : envTemplates) {
                String name = t.path("name").asText(null);
                String value = t.path("value").asText("");
                if (StringUtils.hasText(name)) {
                    env.put(name, render(value, placeholders));
                }
            }
        }
        // Profile 自由环境变量（跟 Profile 走；端口变量也在这里用 {ports.<key>} 声明）
        applyProfileEnvVars(cfg, env, placeholders);
        // 平台执行约定（code-tools 读取）
        applyExecutionEnv(cfg, env);
        // 平台集成测试约定变量
        applyTestingEnv(cfg, env, servicePorts);

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
        runtime.put("env", env);
        runtime.put("cacheMounts", cache);
        if (imageOverride != null) {
            runtime.put("imageOverride", imageOverride);
        }

        return new Instantiated(runtime, env, publishes, cache, imageOverride, preferred, ttl, integration);
    }

    /**
     * 实际映射的服务：容器里每个对外端口都来自 ports.services（key + containerPort）。
     * 同一容器端口只映射一次，key 冲突时追加端口号。
     */
    static List<Map<String, Object>> effectiveServices(JsonNode cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> seenPorts = new HashSet<>();
        Set<String> keys = new HashSet<>();
        JsonNode services = cfg.path("ports").path("services");
        if (services.isArray()) {
            for (JsonNode svc : services) {
                addService(out, seenPorts, keys, svc.path("key").asText(null), svc.path("containerPort").asInt(0));
            }
        }
        return out;
    }

    private static void addService(List<Map<String, Object>> out, Set<Integer> seenPorts, Set<String> keys,
                                   String key, int containerPort) {
        if (!StringUtils.hasText(key) || containerPort <= 0 || !seenPorts.add(containerPort)) {
            return;
        }
        String unique = key.trim();
        while (keys.contains(unique)) {
            unique = unique + "-" + containerPort;
        }
        keys.add(unique);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", unique);
        m.put("containerPort", containerPort);
        out.add(m);
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
    private static void applyProfileEnvVars(JsonNode cfg, Map<String, String> env, Map<String, String> placeholders) {
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
                env.put(name.trim(), render(value, placeholders));
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
                env.put(e.getKey().trim(), render(value, placeholders));
            });
        }
    }

    /**
     * 平台执行约定：code-tools 通过这几个变量读取 Profile 指定的构建/启动/测试命令。
     */
    static void applyExecutionEnv(JsonNode cfg, Map<String, String> env) {
        JsonNode exec = cfg.path("execution");
        putIf(env, "SANDBOX_BUILD_COMMAND", value(exec, "buildCommand"));
        putIf(env, "SANDBOX_START_COMMAND", value(exec, "startCommand"));
        putIf(env, "SANDBOX_TEST_COMMAND", value(exec, "testCommand"));
        putIf(env, "ACTION_TIMEOUT_SEC", value(exec, "actionTimeoutSec"));
    }

    /**
     * 平台集成测试（dap-test）约定的变量：由 testing 开关生成，不属于产品运行时配置。
     */
    static void applyTestingEnv(JsonNode cfg, Map<String, String> env, Map<String, Integer> servicePorts) {
        JsonNode test = cfg.path("testing");
        if (!test.isObject()) {
            return;
        }
        String apiUrl = value(test, "apiUrl");
        if (apiUrl == null) {
            Integer port = servicePort(test, "apiService", servicePorts, "api");
            if (port != null) {
                apiUrl = "http://127.0.0.1:" + port;
            }
        }
        String uiUrl = value(test, "uiUrl");
        if (uiUrl == null) {
            Integer port = servicePort(test, "uiService", servicePorts, "web");
            if (port != null) {
                uiUrl = "http://127.0.0.1:" + port;
            }
        }
        putIf(env, "DAP_TEST_API_URL", apiUrl);
        putIf(env, "DAP_TEST_UI_URL", uiUrl);
        putIf(env, "DAP_TEST_USERNAME", value(test, "username"));
        putIf(env, "DAP_TEST_PASSWORD", value(test, "password"));
        boolean mock = "mock".equalsIgnoreCase(value(test, "modelMode"));
        env.put("DAP_TEST_MODEL_MODE", mock ? "mock" : "real");
        if (mock) {
            // Mock 测试：被测后端的 Agent、审查模型和沙箱管理都指向 dap-test 启动的本地 Mock
            String mockPort = value(test, "mockPort") == null ? "18202" : value(test, "mockPort");
            String mockUrl = "http://127.0.0.1:" + mockPort;
            env.put("DAP_TEST_MOCK_PORT", mockPort);
            env.put("DAP_AGENT_BASEURL", mockUrl);
            env.put("DAP_REVIEW_LLM_BASE_URL", mockUrl + "/v1");
            env.put("DAP_REVIEW_LLM_API_KEY", "mock-only");
            env.put("DAP_REVIEW_LLM_CHAT_MODEL", "mock");
            env.put("DAP_REVIEW_KNOWLEDGE_BASE_URL", mockUrl);
            env.put("DAP_SANDBOX_ENABLED", "true");
            env.put("DAP_SANDBOX_MANAGERBASEURL", mockUrl);
        }
    }

    /** 测试地址引用的容器端口：优先 apiService/uiService 指定的 key，留空时按惯用 key 兜底。 */
    private static Integer servicePort(JsonNode test, String field, Map<String, Integer> servicePorts, String defaultKey) {
        if (servicePorts == null || servicePorts.isEmpty()) {
            return null;
        }
        String key = value(test, field);
        Integer port = key == null ? null : servicePorts.get(key);
        return port != null ? port : servicePorts.get(defaultKey);
    }

    /** 单遍替换已知占位符，未知占位符原样保留，避免误伤密码等含花括号的值。 */
    static String render(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = placeholders.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String value(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull() || !n.isValueNode()) {
            return null;
        }
        String v = n.asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static void putIf(Map<String, String> env, String name, String value) {
        if (value != null) {
            env.put(name, value);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode root, String pointer, String def) {
        JsonNode n = root.at(pointer);
        if (n == null || n.isMissingNode() || n.isNull() || !n.isValueNode()) {
            return def;
        }
        String v = n.asText();
        return StringUtils.hasText(v) ? v : def;
    }
}
