package com.lonple.dap.server.modules.sandbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.mapper.ProductMapper;
import com.lonple.dap.server.modules.sandbox.entity.SbProductResource;
import com.lonple.dap.server.modules.sandbox.entity.SbRuntimeProfile;
import com.lonple.dap.server.modules.sandbox.mapper.SbProductResourceMapper;
import com.lonple.dap.server.modules.sandbox.mapper.SbRuntimeProfileMapper;
import com.lonple.dap.server.security.UserContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SandboxProfileService {

    private final SbRuntimeProfileMapper profileMapper;
    private final SbProductResourceMapper resourceMapper;
    private final ProductMapper productMapper;
    private final SandboxRuntimeInstantator instantator;
    private final ObjectMapper objectMapper;
    private final SandboxLeaseService leaseService;

    public SandboxProfileService(
            SbRuntimeProfileMapper profileMapper,
            SbProductResourceMapper resourceMapper,
            ProductMapper productMapper,
            SandboxRuntimeInstantator instantator,
            ObjectMapper objectMapper,
            @Lazy SandboxLeaseService leaseService) {
        this.profileMapper = profileMapper;
        this.resourceMapper = resourceMapper;
        this.productMapper = productMapper;
        this.instantator = instantator;
        this.objectMapper = objectMapper;
        this.leaseService = leaseService;
    }

    public List<Map<String, Object>> listProfiles(Long productId) {
        requireProduct(productId);
        return profileMapper.selectList(new LambdaQueryWrapper<SbRuntimeProfile>()
                        .eq(SbRuntimeProfile::getProductId, productId)
                        .orderByDesc(SbRuntimeProfile::getIsDefault)
                        .orderByAsc(SbRuntimeProfile::getId))
                .stream()
                .map(this::toProfileView)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> upsertProfile(Long productId, ProfileSaveReq req) {
        requireProduct(productId);
        if (req == null || !StringUtils.hasText(req.profileCode)) {
            throw new BizException("profileCode 必填");
        }
        Long tenantId = requireTenant();
        String code = req.profileCode.trim();
        SbRuntimeProfile existing = profileMapper.selectOne(new LambdaQueryWrapper<SbRuntimeProfile>()
                .eq(SbRuntimeProfile::getProductId, productId)
                .eq(SbRuntimeProfile::getProfileCode, code)
                .last("limit 1"));
        String config = StringUtils.hasText(req.configJson) ? req.configJson : RuntimeProfileDefaults.DEFAULT_CONFIG_JSON;
        validateJson(config);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new SbRuntimeProfile();
            existing.setTenantId(tenantId);
            existing.setProductId(productId);
            existing.setProfileCode(code);
            existing.setDisplayName(StringUtils.hasText(req.displayName) ? req.displayName : code);
            existing.setIsDefault(Boolean.TRUE.equals(req.isDefault));
            existing.setConfigJson(config);
            existing.setStatus(StringUtils.hasText(req.status) ? req.status : "ACTIVE");
            existing.setVersion(1);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            profileMapper.insert(existing);
        } else {
            existing.setDisplayName(StringUtils.hasText(req.displayName) ? req.displayName : existing.getDisplayName());
            existing.setConfigJson(config);
            if (StringUtils.hasText(req.status)) {
                existing.setStatus(req.status);
            }
            existing.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
            existing.setUpdatedAt(now);
            if (req.isDefault != null) {
                existing.setIsDefault(req.isDefault);
            }
            profileMapper.updateById(existing);
        }
        if (Boolean.TRUE.equals(existing.getIsDefault())) {
            clearOtherDefaults(productId, existing.getId());
        }
        return toProfileView(existing);
    }

    @Transactional
    public Map<String, Object> setDefault(Long productId, String profileCode) {
        SbRuntimeProfile p = requireProfile(productId, profileCode);
        p.setIsDefault(true);
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        clearOtherDefaults(productId, p.getId());
        return toProfileView(p);
    }

    @Transactional
    public void deleteProfile(Long productId, String profileCode) {
        SbRuntimeProfile p = requireProfile(productId, profileCode);
        if (Boolean.TRUE.equals(p.getIsDefault())) {
            throw new BizException("不能删除默认 Profile，请先指定其他默认项");
        }
        profileMapper.deleteById(p.getId());
    }

    public Map<String, Object> preview(Long productId, String profileCode, String sampleReqNo) {
        SysProduct product = requireProduct(productId);
        SbRuntimeProfile profile = resolveProfile(productId, profileCode);
        String reqNo = StringUtils.hasText(sampleReqNo) ? sampleReqNo : "SAMPLE-0001";
        Long tenantId = product.getTenantId() != null ? product.getTenantId() : requireTenant();
        return instantator.preview(product, profile, reqNo, "dev-integration", tenantId);
    }

    @Transactional
    public Map<String, Object> ensureDefaultBundle(Long productId) {
        SysProduct product = requireProduct(productId);
        leaseService.ensureProductResource(productId, "webdev");
        SbRuntimeProfile def = profileMapper.selectOne(new LambdaQueryWrapper<SbRuntimeProfile>()
                .eq(SbRuntimeProfile::getProductId, productId)
                .eq(SbRuntimeProfile::getIsDefault, true)
                .eq(SbRuntimeProfile::getStatus, "ACTIVE")
                .last("limit 1"));
        if (def == null) {
            ProfileSaveReq req = new ProfileSaveReq();
            req.profileCode = "default";
            req.displayName = "默认运行方案（待配置）";
            req.isDefault = true;
            req.configJson = RuntimeProfileDefaults.DEFAULT_CONFIG_JSON;
            upsertProfile(productId, req);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", productId);
        out.put("productCode", product.getCode());
        out.put("resources", listResources(productId));
        out.put("profiles", listProfiles(productId));
        return out;
    }

    public List<Map<String, Object>> listResources(Long productId) {
        requireProduct(productId);
        return resourceMapper.selectList(new LambdaQueryWrapper<SbProductResource>()
                        .eq(SbProductResource::getProductId, productId)
                        .orderByAsc(SbProductResource::getId))
                .stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("resourceCode", r.getResourceCode());
                    m.put("image", r.getImage());
                    m.put("maxSlots", r.getMaxSlots());
                    m.put("status", r.getStatus());
                    m.put("nodeId", r.getNodeId());
                    return m;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> upsertResource(Long productId, ResourceSaveReq req) {
        requireProduct(productId);
        if (req == null || !StringUtils.hasText(req.resourceCode)) {
            throw new BizException("resourceCode 必填");
        }
        return leaseService.upsertResource(productId, req);
    }

    public SbRuntimeProfile resolveProfile(Long productId, String profileCode) {
        if (StringUtils.hasText(profileCode)) {
            return requireProfile(productId, profileCode.trim());
        }
        SbRuntimeProfile def = profileMapper.selectOne(new LambdaQueryWrapper<SbRuntimeProfile>()
                .eq(SbRuntimeProfile::getProductId, productId)
                .eq(SbRuntimeProfile::getIsDefault, true)
                .eq(SbRuntimeProfile::getStatus, "ACTIVE")
                .last("limit 1"));
        if (def == null) {
            throw new BizException(404, "SB_PROFILE_NOT_FOUND: 产品无默认 Profile，请先 ensure 或创建");
        }
        return def;
    }

    private SbRuntimeProfile requireProfile(Long productId, String profileCode) {
        SbRuntimeProfile p = profileMapper.selectOne(new LambdaQueryWrapper<SbRuntimeProfile>()
                .eq(SbRuntimeProfile::getProductId, productId)
                .eq(SbRuntimeProfile::getProfileCode, profileCode)
                .last("limit 1"));
        if (p == null || !"ACTIVE".equals(p.getStatus())) {
            throw new BizException(404, "SB_PROFILE_NOT_FOUND: " + profileCode);
        }
        return p;
    }

    private void clearOtherDefaults(Long productId, Long keepId) {
        profileMapper.update(null, new LambdaUpdateWrapper<SbRuntimeProfile>()
                .eq(SbRuntimeProfile::getProductId, productId)
                .ne(SbRuntimeProfile::getId, keepId)
                .set(SbRuntimeProfile::getIsDefault, false));
    }

    private Map<String, Object> toProfileView(SbRuntimeProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("profileCode", p.getProfileCode());
        m.put("displayName", p.getDisplayName());
        m.put("isDefault", Boolean.TRUE.equals(p.getIsDefault()));
        m.put("status", p.getStatus());
        m.put("version", p.getVersion());
        m.put("configJson", p.getConfigJson());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private SysProduct requireProduct(Long productId) {
        if (productId == null) {
            throw new BizException("productId 必填");
        }
        SysProduct p = productMapper.selectById(productId);
        if (p == null || !java.util.Objects.equals(p.getTenantId(), requireTenant())) {
            throw new BizException(404, "产品不存在");
        }
        return p;
    }

    private void validateJson(String json) {
        try {
            JsonNode n = objectMapper.readTree(json);
            if (n == null || !n.isObject()) {
                throw new BizException("configJson 须为 JSON 对象");
            }
            SandboxRuntimeInstantator.effectiveServices(n);
            java.util.Set<String> names = new java.util.HashSet<>();
            if (n.path("envVars").isArray()) {
                for (JsonNode row : n.path("envVars")) {
                    String name = row.path("name").asText(row.path("envName").asText(""));
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !names.add(name)) {
                        throw new BizException("环境变量名称非法或重复：" + name);
                    }
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("configJson 非法: " + e.getMessage());
        }
    }

    private Long requireTenant() {
        if (UserContext.get() == null || UserContext.get().getCurrentTenantId() == null) {
            throw new BizException(401, "未登录");
        }
        return UserContext.get().getCurrentTenantId();
    }

    public static class ProfileSaveReq {
        public String profileCode;
        public String displayName;
        public Boolean isDefault;
        public String configJson;
        public String status;
    }

    public static class ResourceSaveReq {
        public String resourceCode;
        public String image;
        public Integer maxSlots;
        public String status;
        public Long nodeId;
    }
}
