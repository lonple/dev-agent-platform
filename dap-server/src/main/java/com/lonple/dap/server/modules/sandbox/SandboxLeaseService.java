package com.lonple.dap.server.modules.sandbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.ObjectMapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.mapper.ProductMapper;
import com.lonple.dap.server.modules.sandbox.entity.SbLease;
import com.lonple.dap.server.modules.sandbox.entity.SbNode;
import com.lonple.dap.server.modules.sandbox.entity.SbProductResource;
import com.lonple.dap.server.modules.sandbox.entity.SbRuntimeProfile;
import com.lonple.dap.server.modules.sandbox.mapper.SbLeaseMapper;
import com.lonple.dap.server.modules.sandbox.mapper.SbNodeMapper;
import com.lonple.dap.server.modules.sandbox.mapper.SbProductResourceMapper;
import com.lonple.dap.server.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SandboxLeaseService {

    private final SandboxProperties props;
    private final SbNodeMapper nodeMapper;
    private final SbProductResourceMapper resourceMapper;
    private final SbLeaseMapper leaseMapper;
    private final ProductMapper productMapper;
    private final SandboxManagerClient managerClient;
    private final SandboxPortAllocator portAllocator;
    private final SandboxRuntimeInstantator instantator;
    private final ObjectMapper objectMapper;
    private final SandboxProfileService profileService;

    public SandboxLeaseService(
            SandboxProperties props,
            SbNodeMapper nodeMapper,
            SbProductResourceMapper resourceMapper,
            SbLeaseMapper leaseMapper,
            ProductMapper productMapper,
            SandboxManagerClient managerClient,
            SandboxPortAllocator portAllocator,
            SandboxRuntimeInstantator instantator,
            ObjectMapper objectMapper,
            @Lazy SandboxProfileService profileService) {
        this.props = props;
        this.nodeMapper = nodeMapper;
        this.resourceMapper = resourceMapper;
        this.leaseMapper = leaseMapper;
        this.productMapper = productMapper;
        this.managerClient = managerClient;
        this.portAllocator = portAllocator;
        this.instantator = instantator;
        this.objectMapper = objectMapper;
        this.profileService = profileService;
    }

    @Transactional
    public Map<String, Object> ensureProductResource(Long productId, String resourceCode) {
        Long tenantId = requireTenant();
        SbNode node = requireDefaultNode();
        String code = StringUtils.hasText(resourceCode) ? resourceCode.trim() : "webdev";
        SbProductResource res = resourceMapper.selectOne(new LambdaQueryWrapper<SbProductResource>()
                .eq(SbProductResource::getProductId, productId)
                .eq(SbProductResource::getResourceCode, code)
                .last("limit 1"));
        if (res == null) {
            res = new SbProductResource();
            res.setTenantId(tenantId);
            res.setProductId(productId);
            res.setResourceCode(code);
            res.setNodeId(node.getId());
            res.setImage(props.getDefaultImage());
            res.setMaxSlots(3);
            res.setStatus("ACTIVE");
            res.setCreatedAt(LocalDateTime.now());
            res.setUpdatedAt(LocalDateTime.now());
            resourceMapper.insert(res);
        }
        return resourceView(res, node);
    }

    @Transactional
    public Map<String, Object> upsertResource(Long productId, SandboxProfileService.ResourceSaveReq req) {
        Long tenantId = requireTenant();
        SbNode node = requireDefaultNode();
        String code = req.resourceCode.trim();
        SbProductResource res = resourceMapper.selectOne(new LambdaQueryWrapper<SbProductResource>()
                .eq(SbProductResource::getProductId, productId)
                .eq(SbProductResource::getResourceCode, code)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (res == null) {
            res = new SbProductResource();
            res.setTenantId(tenantId);
            res.setProductId(productId);
            res.setResourceCode(code);
            res.setNodeId(req.nodeId != null ? req.nodeId : node.getId());
            res.setImage(StringUtils.hasText(req.image) ? req.image : props.getDefaultImage());
            res.setMaxSlots(req.maxSlots == null ? 3 : req.maxSlots);
            res.setStatus(StringUtils.hasText(req.status) ? req.status : "ACTIVE");
            res.setCreatedAt(now);
            res.setUpdatedAt(now);
            resourceMapper.insert(res);
        } else {
            if (StringUtils.hasText(req.image)) {
                res.setImage(req.image);
            }
            if (req.maxSlots != null) {
                res.setMaxSlots(req.maxSlots);
            }
            if (StringUtils.hasText(req.status)) {
                res.setStatus(req.status);
            }
            if (req.nodeId != null) {
                res.setNodeId(req.nodeId);
            }
            res.setUpdatedAt(now);
            resourceMapper.updateById(res);
        }
        SbNode n = nodeMapper.selectById(res.getNodeId());
        return resourceView(res, n);
    }

    public Map<String, Object> previewInstance(
            SysProduct product, SbRuntimeProfile profile, String reqNo, String integrationEnvCode) {
        Long tenantId = product.getTenantId() != null ? product.getTenantId() : requireTenant();
        return instantator.preview(product, profile, reqNo, integrationEnvCode, tenantId);
    }

    @Transactional
    public Map<String, Object> acquire(AcquireReq req) {
        if (req == null || req.productId == null) {
            throw new BizException("productId 必填");
        }
        if (!StringUtils.hasText(req.worktreeHostPath)) {
            throw new BizException("worktreeHostPath 必填");
        }
        Path wt = Path.of(req.worktreeHostPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(wt)) {
            throw new BizException("worktreeHostPath 不是目录: " + wt);
        }
        Long tenantId = requireTenant();
        SysProduct product = productMapper.selectById(req.productId);
        if (product == null) {
            throw new BizException(404, "产品不存在");
        }

        // 已有 ATTACHED 则复用（同会话 / 同产品同 worktree），避免重复 docker create 抢端口
        SbLease existing = findReusableAttached(req.sessionId, req.productId, wt.toString());
        if (existing != null) {
            existing.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, props.getDefaultLeaseTtlHours())));
            existing.setUpdatedAt(LocalDateTime.now());
            if (req.sessionId != null) {
                existing.setSessionId(req.sessionId);
            }
            if (req.jobId != null) {
                existing.setJobId(req.jobId);
            }
            leaseMapper.updateById(existing);
            SbProductResource res = resourceMapper.selectById(existing.getResourceId());
            SbNode node = res == null ? null : nodeMapper.selectById(res.getNodeId());
            log.info("sandbox lease reused id={} sessionId={} worktree={}",
                    existing.getId(), existing.getSessionId(), existing.getWorktreeHostPath());
            return toView(existing, res, node);
        }

        profileService.ensureDefaultBundle(req.productId);
        SbRuntimeProfile profile = profileService.resolveProfile(req.productId, req.profileCode);

        // resource_id 列 NOT NULL：须在 insert 前选好席位，再占 lease id 去分配端口
        SbProductResource res = pickResource(
                req.productId, instantator.preferredResourceCodes(profile), req.resourceCode);
        SbNode node = nodeMapper.selectById(res.getNodeId());
        if (node == null) {
            throw new BizException("资源绑定节点不存在");
        }

        SbLease lease = new SbLease();
        lease.setTenantId(tenantId);
        lease.setProductId(req.productId);
        lease.setResourceId(res.getId());
        lease.setProfileId(profile.getId());
        lease.setProfileVersion(profile.getVersion());
        lease.setReqNo(req.reqNo);
        lease.setSessionId(req.sessionId);
        lease.setJobId(req.jobId);
        lease.setWorktreeHostPath(wt.toString());
        lease.setPurpose(StringUtils.hasText(req.purpose) ? req.purpose : "DEV");
        lease.setStatus("REQUESTED");
        lease.setCreatedAt(LocalDateTime.now());
        lease.setUpdatedAt(LocalDateTime.now());
        leaseMapper.insert(lease);

        SandboxRuntimeInstantator.Instantiated inst;
        try {
            inst = instantator.instantiate(
                    product,
                    profile,
                    req.reqNo,
                    req.integrationEnvCode,
                    tenantId,
                    (serviceKey, poolStart, poolEnd) -> portAllocator.allocate(
                            lease.getId(), null, serviceKey, poolStart, poolEnd));
        } catch (RuntimeException e) {
            lease.setStatus("CLOSED");
            lease.setClosedAt(LocalDateTime.now());
            lease.setUpdatedAt(LocalDateTime.now());
            leaseMapper.updateById(lease);
            portAllocator.releaseByLease(lease.getId());
            throw e;
        }

        lease.setIntegrationEnvCode(inst.integrationEnvCode());
        lease.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, inst.leaseTtlHours())));

        String image = StringUtils.hasText(inst.imageOverride()) ? inst.imageOverride() : res.getImage();
        // env 已含 Profile 模板变量 + Profile.envVars（跟所选 Profile 走）
        Map<String, String> containerEnv = new LinkedHashMap<>();
        if (inst.env() != null) {
            containerEnv.putAll(inst.env());
        }
        inst.runtimeInstance().put("image", image);
        inst.runtimeInstance().put("nodeCode", node.getNodeCode());
        inst.runtimeInstance().put("worktreeHostPath", wt.toString());
        inst.runtimeInstance().put("resourceCode", res.getResourceCode());
        inst.runtimeInstance().put("env", containerEnv);

        try {
            lease.setRuntimeInstanceJson(objectMapper.writeValueAsString(inst.runtimeInstance()));
        } catch (Exception e) {
            portAllocator.releaseByLease(lease.getId());
            lease.setStatus("CLOSED");
            lease.setClosedAt(LocalDateTime.now());
            leaseMapper.updateById(lease);
            throw new BizException("runtime_instance 序列化失败");
        }
        leaseMapper.updateById(lease);

        if (props.isAttachOnLease()) {
            try {
                managerClient.attach(
                        lease.getId(),
                        node.getNodeCode(),
                        image,
                        wt.toString(),
                        containerEnv,
                        inst.portPublishes(),
                        toCacheMountMap(inst.cacheMounts()));
                lease.setStatus("ATTACHED");
                lease.setUpdatedAt(LocalDateTime.now());
                leaseMapper.updateById(lease);
            } catch (RuntimeException e) {
                portAllocator.releaseByLease(lease.getId());
                lease.setStatus("CLOSED");
                lease.setClosedAt(LocalDateTime.now());
                lease.setUpdatedAt(LocalDateTime.now());
                leaseMapper.updateById(lease);
                throw e;
            }
        } else {
            lease.setStatus("ATTACHED");
            leaseMapper.updateById(lease);
        }
        return toView(lease, res, node);
    }

    @Transactional
    public Map<String, Object> release(Long leaseId) {
        SbLease lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new BizException(404, "lease 不存在");
        }
        if ("CLOSED".equals(lease.getStatus())) {
            return Map.of("leaseId", leaseId, "status", "CLOSED");
        }
        try {
            managerClient.detach(leaseId);
        } catch (Exception e) {
            log.warn("detach lease={} failed: {}", leaseId, e.getMessage());
        }
        portAllocator.releaseByLease(leaseId);
        lease.setStatus("CLOSED");
        lease.setClosedAt(LocalDateTime.now());
        lease.setUpdatedAt(LocalDateTime.now());
        leaseMapper.updateById(lease);
        return Map.of("leaseId", leaseId, "status", "CLOSED");
    }

    /**
     * 等待人工确认：不立刻关容器，只把过期时间收到「空闲 N 分钟」；
     * 到期由 reclaimExpired 释放。永不删除 worktree。
     */
    @Transactional
    public void armConfirmIdleRelease(Long sessionId, int idleMinutes) {
        if (sessionId == null) {
            return;
        }
        int minutes = idleMinutes > 0 ? idleMinutes : props.getConfirmIdleReleaseMinutes();
        SbLease lease = findAttachedBySession(sessionId);
        if (lease == null) {
            return;
        }
        LocalDateTime idleDeadline = LocalDateTime.now().plusMinutes(minutes);
        // 只缩短、不延长（避免把更短的空闲窗口拉长）
        if (lease.getExpiresAt() == null || lease.getExpiresAt().isAfter(idleDeadline)) {
            lease.setExpiresAt(idleDeadline);
            lease.setUpdatedAt(LocalDateTime.now());
            leaseMapper.updateById(lease);
            log.info("sandbox confirm-idle armed lease={} expiresAt={}", lease.getId(), idleDeadline);
        }
    }

    @Transactional
    public int reclaimExpired() {
        List<SbLease> expired = leaseMapper.selectList(new LambdaQueryWrapper<SbLease>()
                .eq(SbLease::getStatus, "ATTACHED")
                .isNotNull(SbLease::getExpiresAt)
                .lt(SbLease::getExpiresAt, LocalDateTime.now()));
        int n = 0;
        for (SbLease lease : expired) {
            try {
                // release 只停容器/归还端口，不碰 worktree 目录
                release(lease.getId());
                n++;
            } catch (Exception e) {
                log.warn("reclaim lease={} failed: {}", lease.getId(), e.getMessage());
            }
        }
        return n;
    }

    public SbLease requireAttachedBySession(Long sessionId) {
        SbLease lease = findAttachedBySession(sessionId);
        if (lease == null) {
            throw new BizException(409, "会话未绑定已附着的沙箱 lease，请先申请沙箱");
        }
        return lease;
    }

    public SbLease findAttachedBySession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return leaseMapper.selectOne(new LambdaQueryWrapper<SbLease>()
                .eq(SbLease::getSessionId, sessionId)
                .eq(SbLease::getStatus, "ATTACHED")
                .orderByDesc(SbLease::getId)
                .last("limit 1"));
    }

    /**
     * 可复用租约：同会话 ATTACHED，或同产品 + 同 worktree 的 ATTACHED（流水线重跑会换 session）。
     */
    private SbLease findReusableAttached(Long sessionId, Long productId, String worktreeHostPath) {
        if (sessionId != null) {
            SbLease bySession = findAttachedBySession(sessionId);
            if (bySession != null) {
                return bySession;
            }
        }
        if (productId == null || !StringUtils.hasText(worktreeHostPath)) {
            return null;
        }
        String normalized = Path.of(worktreeHostPath.trim()).toAbsolutePath().normalize().toString();
        List<SbLease> candidates = leaseMapper.selectList(new LambdaQueryWrapper<SbLease>()
                .eq(SbLease::getProductId, productId)
                .eq(SbLease::getStatus, "ATTACHED")
                .orderByDesc(SbLease::getId)
                .last("limit 20"));
        for (SbLease lease : candidates) {
            if (!StringUtils.hasText(lease.getWorktreeHostPath())) {
                continue;
            }
            String other = Path.of(lease.getWorktreeHostPath().trim()).toAbsolutePath().normalize().toString();
            if (normalized.equalsIgnoreCase(other)) {
                return lease;
            }
        }
        return null;
    }

    public SbLease findAttachedByJob(Long jobId) {
        if (jobId == null) {
            return null;
        }
        return leaseMapper.selectOne(new LambdaQueryWrapper<SbLease>()
                .eq(SbLease::getJobId, jobId)
                .eq(SbLease::getStatus, "ATTACHED")
                .orderByDesc(SbLease::getId)
                .last("limit 1"));
    }

    public List<SbLease> listActiveByProduct(Long productId) {
        return leaseMapper.selectList(new LambdaQueryWrapper<SbLease>()
                .eq(SbLease::getProductId, productId)
                .eq(SbLease::getStatus, "ATTACHED")
                .orderByDesc(SbLease::getId));
    }

    private SbProductResource pickResource(Long productId, List<String> preferred, String explicitCode) {
        if (StringUtils.hasText(explicitCode)) {
            SbProductResource res = resourceMapper.selectOne(new LambdaQueryWrapper<SbProductResource>()
                    .eq(SbProductResource::getProductId, productId)
                    .eq(SbProductResource::getResourceCode, explicitCode.trim())
                    .eq(SbProductResource::getStatus, "ACTIVE")
                    .last("limit 1"));
            if (res == null) {
                throw new BizException("无可用沙箱资源: " + explicitCode);
            }
            assertCapacity(res);
            return res;
        }
        if (preferred != null) {
            for (String code : preferred) {
                SbProductResource res = resourceMapper.selectOne(new LambdaQueryWrapper<SbProductResource>()
                        .eq(SbProductResource::getProductId, productId)
                        .eq(SbProductResource::getResourceCode, code)
                        .eq(SbProductResource::getStatus, "ACTIVE")
                        .last("limit 1"));
                if (res != null && hasCapacity(res)) {
                    return res;
                }
            }
        }
        List<SbProductResource> all = resourceMapper.selectList(new LambdaQueryWrapper<SbProductResource>()
                .eq(SbProductResource::getProductId, productId)
                .eq(SbProductResource::getStatus, "ACTIVE")
                .orderByAsc(SbProductResource::getId));
        for (SbProductResource res : all) {
            if (hasCapacity(res)) {
                return res;
            }
        }
        throw new BizException(409, "SB_NO_CAPACITY: 沙箱席位已满");
    }

    private void assertCapacity(SbProductResource res) {
        if (!hasCapacity(res)) {
            throw new BizException(409, "SB_NO_CAPACITY: 沙箱席位已满");
        }
    }

    private boolean hasCapacity(SbProductResource res) {
        long used = leaseMapper.selectCount(new LambdaQueryWrapper<SbLease>()
                .eq(SbLease::getResourceId, res.getId())
                .eq(SbLease::getStatus, "ATTACHED"));
        int max = res.getMaxSlots() == null ? 3 : res.getMaxSlots();
        return used < max;
    }

    private SbNode requireDefaultNode() {
        SbNode node = nodeMapper.selectOne(new LambdaQueryWrapper<SbNode>()
                .eq(SbNode::getNodeCode, props.getDefaultNodeCode())
                .last("limit 1"));
        if (node == null) {
            throw new BizException("默认沙箱节点不存在，请检查 sb_node");
        }
        return node;
    }

    private Map<String, Object> resourceView(SbProductResource res, SbNode node) {
        Map<String, Object> out = new HashMap<>();
        out.put("resourceId", res.getId());
        out.put("resourceCode", res.getResourceCode());
        out.put("image", res.getImage());
        out.put("maxSlots", res.getMaxSlots());
        out.put("status", res.getStatus());
        out.put("nodeCode", node == null ? null : node.getNodeCode());
        return out;
    }

    private Map<String, Object> toView(SbLease lease, SbProductResource res, SbNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leaseId", lease.getId());
        out.put("resourceId", res == null ? null : res.getId());
        out.put("resourceCode", res == null ? null : res.getResourceCode());
        out.put("nodeCode", node == null ? null : node.getNodeCode());
        out.put("status", lease.getStatus());
        out.put("worktreeHostPath", lease.getWorktreeHostPath());
        out.put("image", res == null ? null : res.getImage());
        out.put("sessionId", lease.getSessionId());
        out.put("jobId", lease.getJobId());
        out.put("profileId", lease.getProfileId());
        out.put("integrationEnvCode", lease.getIntegrationEnvCode());
        out.put("expiresAt", lease.getExpiresAt());
        if (StringUtils.hasText(lease.getRuntimeInstanceJson())) {
            try {
                out.put("runtimeInstance", objectMapper.readValue(lease.getRuntimeInstanceJson(), Map.class));
            } catch (Exception ignored) {
                out.put("runtimeInstanceJson", lease.getRuntimeInstanceJson());
            }
        }
        return out;
    }

    private static Map<String, String> toCacheMountMap(Map<String, Boolean> cache) {
        Map<String, String> m = new HashMap<>();
        if (cache == null) {
            return m;
        }
        cache.forEach((k, v) -> m.put(k, Boolean.TRUE.equals(v) ? "true" : "false"));
        return m;
    }

    private Long requireTenant() {
        if (UserContext.get() == null || UserContext.get().getCurrentTenantId() == null) {
            throw new BizException(401, "未登录");
        }
        return UserContext.get().getCurrentTenantId();
    }

    public static class AcquireReq {
        public Long productId;
        public String resourceCode;
        public String profileCode;
        public String integrationEnvCode;
        public String reqNo;
        public Long sessionId;
        public Long jobId;
        public String worktreeHostPath;
        public String purpose;
    }
}
