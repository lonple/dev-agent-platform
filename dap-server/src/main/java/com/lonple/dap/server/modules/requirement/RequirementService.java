package com.lonple.dap.server.modules.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.position.mapper.PositionMapper;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.ProductVersionStatus;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.entity.SysProductVersion;
import com.lonple.dap.server.modules.requirement.dto.ApproverCandidatesVO;
import com.lonple.dap.server.modules.requirement.dto.RequirementFlowVO;
import com.lonple.dap.server.modules.requirement.dto.RequirementHandlerAddRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementHandlerVO;
import com.lonple.dap.server.modules.requirement.dto.RequirementPlanRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementRejectRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementSaveRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementSubmitRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementVO;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirementFlow;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirementHandler;
import com.lonple.dap.server.modules.requirement.mapper.RequirementFlowMapper;
import com.lonple.dap.server.modules.requirement.mapper.RequirementHandlerMapper;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.requirement.mapper.RequirementNoSeqMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RequirementService {

    private static final Set<String> EDITABLE = Set.of(
            RequirementStatus.DRAFT.name(),
            RequirementStatus.REJECTED.name());

    private final RequirementMapper requirementMapper;
    private final RequirementFlowMapper flowMapper;
    private final RequirementHandlerMapper handlerMapper;
    private final RequirementNoSeqMapper requirementNoSeqMapper;
    private final ProductService productService;
    private final PositionService positionService;
    private final PositionMapper positionMapper;
    private final SysUserMapper userMapper;
    private final TenantUserMapper tenantUserMapper;
    private final WikiFsService wikiFsService;
    private final ObjectMapper objectMapper;

    public ApproverCandidatesVO listApproverCandidates(Long productId) {
        Long tenantId = TenantSupport.requireTenantId();
        SysProduct product = productService.requireOwned(productId);

        ApproverCandidatesVO vo = new ApproverCandidatesVO();
        Long managerId = product.getManagerUserId();
        vo.setProductManagerId(managerId);
        vo.setProductManagerUsername(usernameOf(managerId));

        List<SysUser> members = new java.util.ArrayList<>(
                tenantUserMapper.selectUsersByTenantId(tenantId));

        // 产品经理不在成员列表时补进候选（允许申请人与审批人为同一人）
        if (managerId != null && members.stream().noneMatch(u -> Objects.equals(u.getId(), managerId))) {
            SysUser manager = userMapper.selectById(managerId);
            if (manager != null) {
                members.add(0, manager);
            }
        }

        for (SysUser u : members) {
            ApproverCandidatesVO.Candidate c = new ApproverCandidatesVO.Candidate();
            c.setId(u.getId());
            c.setUsername(u.getUsername());
            c.setProductManager(Objects.equals(u.getId(), managerId));
            vo.getCandidates().add(c);
        }

        if (managerId != null
                && vo.getCandidates().stream().anyMatch(c -> Objects.equals(c.getId(), managerId))) {
            vo.setDefaultApproverId(managerId);
        }
        return vo;
    }

    /** 需求申请：我创建的 */
    public List<RequirementVO> listMine() {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        return requirementMapper.selectList(new LambdaQueryWrapper<ReqRequirement>()
                        .eq(ReqRequirement::getTenantId, tenantId)
                        .eq(ReqRequirement::getCreatedBy, userId)
                        .orderByDesc(ReqRequirement::getId))
                .stream()
                .map(e -> toVO(e, false))
                .toList();
    }

    /**
     * 工作台需求列表：当前处理人=我，或我在「需求处理人」名单中。
     * scope=todo 待办（非草稿/驳回/已发布）；scope=all 全部（非草稿/驳回）。
     */
    public List<RequirementVO> listWorkbench(String scope) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        Set<String> statuses = isAllScope(scope)
                ? RequirementStatus.WORKBENCH_ALL_STATUSES
                : RequirementStatus.TODO_STATUSES;
        List<Long> asHandlerIds = handlerMapper.selectRequirementIdsByUser(tenantId, userId);
        LambdaQueryWrapper<ReqRequirement> q = new LambdaQueryWrapper<ReqRequirement>()
                .eq(ReqRequirement::getTenantId, tenantId)
                .in(ReqRequirement::getStatus, statuses)
                .and(w -> {
                    w.eq(ReqRequirement::getCurrentHandlerId, userId);
                    if (!asHandlerIds.isEmpty()) {
                        w.or().in(ReqRequirement::getId, asHandlerIds);
                    }
                })
                .orderByAsc(ReqRequirement::getProductId)
                .orderByAsc(ReqRequirement::getProductVersion)
                .orderByDesc(ReqRequirement::getUpdatedAt);
        return requirementMapper.selectList(q).stream().map(e -> toVO(e, false)).toList();
    }

    /** @deprecated 兼容旧接口，等同 scope=todo */
    public List<RequirementVO> listInbox() {
        return listWorkbench("todo");
    }

    private static boolean isAllScope(String scope) {
        return scope != null && "all".equalsIgnoreCase(scope.trim());
    }

    public RequirementVO get(Long id) {
        ReqRequirement entity = requireAccessible(id);
        return toVO(entity, true);
    }

    @Transactional
    public Long create(RequirementSaveRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        SysProduct product = productService.requireOwned(req.getProductId());
        SysProductVersion version = requireSelectableProductVersion(
                product.getId(), req.getProductVersionId());

        ReqRequirement entity = new ReqRequirement();
        entity.setTenantId(tenantId);
        entity.setReqNo(nextReqNo(tenantId, product));
        entity.setName(req.getName().trim());
        entity.setProductId(product.getId());
        entity.setProductVersionId(version.getId());
        entity.setProductVersion(version.getVersion());
        entity.setDescription(req.getDescription().trim());
        entity.setStatus(RequirementStatus.DRAFT.name());
        entity.setCreatedBy(userId);
        entity.setCurrentHandlerId(userId);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.insert(entity);
        return entity.getId();
    }

    @Transactional
    public void update(Long id, RequirementSaveRequest req) {
        ReqRequirement entity = requireOwnedByCreator(id);
        assertEditable(entity);
        SysProduct product = productService.requireOwned(req.getProductId());
        SysProductVersion version = requireSelectableProductVersion(
                product.getId(), req.getProductVersionId());
        boolean productChanged = !Objects.equals(entity.getProductId(), product.getId());
        entity.setName(req.getName().trim());
        entity.setProductId(product.getId());
        entity.setProductVersionId(version.getId());
        entity.setProductVersion(version.getVersion());
        entity.setDescription(req.getDescription().trim());
        if (productChanged) {
            entity.setReqNo(nextReqNo(entity.getTenantId(), product));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(entity);
    }

    /** 产品编码-年份-00000（每年按产品从 0 递增） */
    private String nextReqNo(Long tenantId, SysProduct product) {
        if (!StringUtils.hasText(product.getCode())) {
            throw new BizException("产品未配置编码，无法生成需求编号");
        }
        int year = Year.now().getValue();
        requirementNoSeqMapper.ensureRow(tenantId, product.getId(), year);
        Integer last = requirementNoSeqMapper.selectLastSeqForUpdate(tenantId, product.getId(), year);
        if (last == null) {
            throw new BizException("生成需求编号失败");
        }
        int seq = last + 1;
        requirementNoSeqMapper.updateLastSeq(tenantId, product.getId(), year, seq);
        return product.getCode().trim() + "-" + year + "-" + String.format("%05d", seq);
    }

    @Transactional
    public void submit(Long id, RequirementSubmitRequest req) {
        ReqRequirement entity = requireOwnedByCreator(id);
        RequirementStatus current = RequirementStatus.fromCode(entity.getStatus());
        if (current != RequirementStatus.DRAFT && current != RequirementStatus.REJECTED) {
            throw new BizException("仅草稿或驳回状态可提交申请");
        }
        SysProduct product = productService.requireOwned(entity.getProductId());
        Long approverId = resolveApprover(product, entity, req == null ? null : req.getApproverId());
        String from = entity.getStatus();
        entity.setApproverId(approverId);
        entity.setCurrentHandlerId(approverId);
        entity.setStatus(RequirementStatus.APPROVING.name());
        entity.setRejectReason(null);
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(entity);
        appendFlow(entity, RequirementFlowNodeType.SUBMIT, from, RequirementStatus.APPROVING.name(),
                TenantSupport.requireUserId(), "提交申请，审批人：" + usernameOf(approverId), null);
    }

    @Transactional
    public void approve(Long id) {
        ReqRequirement entity = requireCurrentHandler(id);
        if (RequirementStatus.fromCode(entity.getStatus()) != RequirementStatus.APPROVING) {
            throw new BizException("仅审批中状态可通过");
        }
        String from = entity.getStatus();
        entity.setStatus(RequirementStatus.PENDING_PLAN.name());
        entity.setCurrentHandlerId(entity.getApproverId());
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(entity);
        appendFlow(entity, RequirementFlowNodeType.APPROVE, from, RequirementStatus.PENDING_PLAN.name(),
                TenantSupport.requireUserId(), "审批通过", null);
    }

    @Transactional
    public void reject(Long id, RequirementRejectRequest req) {
        ReqRequirement entity = requireCurrentHandler(id);
        if (RequirementStatus.fromCode(entity.getStatus()) != RequirementStatus.APPROVING) {
            throw new BizException("仅审批中状态可驳回");
        }
        String from = entity.getStatus();
        String reason = req.getReason().trim();
        entity.setStatus(RequirementStatus.REJECTED.name());
        entity.setRejectReason(reason);
        entity.setCurrentHandlerId(entity.getCreatedBy());
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(entity);
        appendFlow(entity, RequirementFlowNodeType.REJECT, from, RequirementStatus.REJECTED.name(),
                TenantSupport.requireUserId(), reason, null);
    }

    @Transactional
    public void submitPlan(Long id, RequirementPlanRequest req) {
        ReqRequirement entity = requireCurrentHandler(id);
        if (RequirementStatus.fromCode(entity.getStatus()) != RequirementStatus.PENDING_PLAN) {
            throw new BizException("仅待计划状态可提交计划");
        }
        SysProductVersion releaseVersion = productService.requireVersionOfProduct(
                entity.getProductId(), req.getReleaseVersionId());
        if (ProductVersionStatus.fromCode(releaseVersion.getStatus()) != ProductVersionStatus.PLANNING) {
            throw new BizException("请选择状态为「计划中」的发布版本");
        }
        String from = entity.getStatus();
        entity.setPlanDate(null);
        entity.setReleaseVersion(releaseVersion.getVersion());
        entity.setStatus(RequirementStatus.PLANNING.name());
        entity.setCurrentHandlerId(entity.getApproverId());
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(entity);
        String extra = writeJson(Map.of(
                "releaseVersionId", String.valueOf(releaseVersion.getId()),
                "releaseVersion", releaseVersion.getVersion()));
        String comment = StringUtils.hasText(req.getComment())
                ? req.getComment().trim()
                : "发布版本 " + releaseVersion.getVersion();
        appendFlow(entity, RequirementFlowNodeType.PLAN, from, RequirementStatus.PLANNING.name(),
                TenantSupport.requireUserId(), comment, extra);
    }

    @Transactional
    public void startDevelopment(Long id) {
        ReqRequirement entity = requireCurrentHandler(id);
        if (RequirementStatus.fromCode(entity.getStatus()) != RequirementStatus.PLANNING) {
            throw new BizException("仅计划中状态可投入研发");
        }
        String from = entity.getStatus();
        entity.setStatus(RequirementStatus.DEVELOPING.name());
        entity.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(entity);
        // 投入研发：清空后将当前处理人默认加入需求处理人（岗位为空=负责人）
        handlerMapper.deleteByRequirement(entity.getId());
        ensureHandler(entity, entity.getCurrentHandlerId(), null);
        appendFlow(entity, RequirementFlowNodeType.DEVELOP, from, RequirementStatus.DEVELOPING.name(),
                TenantSupport.requireUserId(), "投入研发", null);

        SysProduct product = productService.requireOwned(entity.getProductId());
        wikiFsService.ensureRequirementArtifacts(product, entity);
    }

    /**
     * 研发中：添加需求处理人（多人）。仅当前处理人或申请人可维护。
     */
    @Transactional
    public void addHandlers(Long id, RequirementHandlerAddRequest req) {
        ReqRequirement entity = requireCanManageHandlers(id);
        List<RequirementHandlerAddRequest.Item> items = new ArrayList<>();
        if (req.getItems() != null) {
            for (RequirementHandlerAddRequest.Item it : req.getItems()) {
                if (it != null) {
                    items.add(it);
                }
            }
        }
        if (items.isEmpty() && req.getPositionId() != null) {
            RequirementHandlerAddRequest.Item one = new RequirementHandlerAddRequest.Item();
            one.setPositionId(req.getPositionId());
            List<Long> ids = new ArrayList<>();
            if (req.getUserId() != null) {
                ids.add(req.getUserId());
            }
            if (req.getUserIds() != null) {
                ids.addAll(req.getUserIds());
            }
            one.setUserIds(ids);
            items.add(one);
        }
        if (items.isEmpty()) {
            throw new BizException("请至少添加一组处理人");
        }
        int added = 0;
        for (RequirementHandlerAddRequest.Item it : items) {
            if (it.getPositionId() == null) {
                throw new BizException("请选择处理人角色（岗位）");
            }
            SysPosition position = positionService.requireOwned(it.getPositionId());
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            if (it.getUserIds() != null) {
                for (Long uid : it.getUserIds()) {
                    if (uid != null) {
                        ids.add(uid);
                    }
                }
            }
            if (ids.isEmpty()) {
                throw new BizException("岗位「" + position.getName() + "」请至少选择一名成员");
            }
            for (Long uid : ids) {
                if (!tenantUserMapper.existsMembership(entity.getTenantId(), uid)) {
                    throw new BizException("用户不属于当前租户：" + usernameOf(uid));
                }
                ensureHandler(entity, uid, position.getId());
                added++;
            }
        }
        if (added == 0) {
            throw new BizException("请选择要添加的处理人");
        }
    }

    /**
     * 研发中：按记录移除处理人角色。当前处理人的「负责人」底册不可删光。
     */
    @Transactional
    public void removeHandler(Long id, Long handlerRecordId) {
        ReqRequirement entity = requireCanManageHandlers(id);
        if (handlerRecordId == null) {
            throw new BizException("记录无效");
        }
        ReqRequirementHandler row = handlerMapper.selectById(handlerRecordId);
        if (row == null || !Objects.equals(row.getRequirementId(), entity.getId())) {
            throw new BizException("处理人记录不存在");
        }
        if (Objects.equals(entity.getCurrentHandlerId(), row.getUserId()) && row.getPositionId() == null) {
            throw new BizException("当前处理人（负责人）不可移除");
        }
        // 当前处理人若只剩这一条有岗位的记录，也不允许删到完全不在名单中
        if (Objects.equals(entity.getCurrentHandlerId(), row.getUserId())) {
            long left = handlerMapper.selectList(new LambdaQueryWrapper<ReqRequirementHandler>()
                            .eq(ReqRequirementHandler::getRequirementId, entity.getId())
                            .eq(ReqRequirementHandler::getUserId, row.getUserId()))
                    .size();
            if (left <= 1) {
                throw new BizException("当前处理人不可从需求处理人中完全移除");
            }
        }
        handlerMapper.deleteByIdAndRequirement(entity.getId(), handlerRecordId);
    }

    @Transactional
    public void delete(Long id) {
        ReqRequirement entity = requireOwnedByCreator(id);
        // 申请人可删除任意状态需求；任务/流程等随外键 CASCADE 清理
        requirementMapper.deleteById(entity.getId());
    }

    private Long resolveApprover(SysProduct product, ReqRequirement entity, Long requested) {
        Long tenantId = product.getTenantId();
        Long approverId = requested;
        if (approverId == null) {
            if (entity.getApproverId() != null) {
                approverId = entity.getApproverId();
            } else if (product.getManagerUserId() != null) {
                approverId = product.getManagerUserId();
            }
        }
        if (approverId == null) {
            throw new BizException("请选择审批人（产品未配置产品经理时可手选）");
        }
        if (!tenantUserMapper.existsMembership(tenantId, approverId)) {
            throw new BizException("审批人不属于当前租户");
        }
        return approverId;
    }

    private void appendFlow(
            ReqRequirement entity,
            RequirementFlowNodeType nodeType,
            String fromStatus,
            String toStatus,
            Long operatorId,
            String comment,
            String extraJson) {
        ReqRequirementFlow flow = new ReqRequirementFlow();
        flow.setTenantId(entity.getTenantId());
        flow.setRequirementId(entity.getId());
        flow.setNodeType(nodeType.name());
        flow.setFromStatus(fromStatus);
        flow.setToStatus(toStatus);
        flow.setOperatorId(operatorId);
        flow.setCommentText(comment);
        flow.setExtraJson(extraJson);
        flow.setCreatedAt(LocalDateTime.now());
        flowMapper.insert(flow);
    }

    private SysProductVersion requireSelectableProductVersion(Long productId, Long versionId) {
        SysProductVersion version = productService.requireVersionOfProduct(productId, versionId);
        if (ProductVersionStatus.fromCode(version.getStatus()) == ProductVersionStatus.INVALID) {
            throw new BizException("不能选择无效版本");
        }
        return version;
    }

    private void assertEditable(ReqRequirement entity) {
        RequirementStatus status = RequirementStatus.fromCode(entity.getStatus());
        if (!EDITABLE.contains(status.name())) {
            throw new BizException("当前状态不可编辑或删除：" + status.getLabel());
        }
    }

    private ReqRequirement requireOwnedByCreator(Long id) {
        ReqRequirement entity = requireInTenant(id);
        if (!Objects.equals(entity.getCreatedBy(), TenantSupport.requireUserId())) {
            throw new BizException(403, "只能操作自己申请的需求");
        }
        return entity;
    }

    private ReqRequirement requireCurrentHandler(Long id) {
        ReqRequirement entity = requireInTenant(id);
        if (!Objects.equals(entity.getCurrentHandlerId(), TenantSupport.requireUserId())) {
            throw new BizException(403, "当前处理人才能操作");
        }
        return entity;
    }

    private ReqRequirement requireAccessible(Long id) {
        ReqRequirement entity = requireInTenant(id);
        Long userId = TenantSupport.requireUserId();
        if (Objects.equals(entity.getCreatedBy(), userId)
                || Objects.equals(entity.getCurrentHandlerId(), userId)
                || Objects.equals(entity.getApproverId(), userId)
                || handlerMapper.exists(entity.getId(), userId)) {
            return entity;
        }
        throw new BizException(403, "无权查看该需求");
    }

    private ReqRequirement requireCanManageHandlers(Long id) {
        ReqRequirement entity = requireInTenant(id);
        if (RequirementStatus.fromCode(entity.getStatus()) != RequirementStatus.DEVELOPING) {
            throw new BizException("仅研发中可维护需求处理人");
        }
        Long userId = TenantSupport.requireUserId();
        if (Objects.equals(entity.getCurrentHandlerId(), userId)
                || Objects.equals(entity.getCreatedBy(), userId)) {
            return entity;
        }
        throw new BizException(403, "仅当前处理人或申请人可维护需求处理人");
    }

    private void ensureHandler(ReqRequirement entity, Long userId, Long positionId) {
        if (entity == null || userId == null) {
            return;
        }
        handlerMapper.insertIgnore(entity.getTenantId(), entity.getId(), userId, positionId);
    }

    private List<RequirementHandlerVO> listHandlers(ReqRequirement entity) {
        List<ReqRequirementHandler> rows = handlerMapper.selectList(new LambdaQueryWrapper<ReqRequirementHandler>()
                .eq(ReqRequirementHandler::getRequirementId, entity.getId())
                .orderByAsc(ReqRequirementHandler::getId));
        List<RequirementHandlerVO> list = new ArrayList<>();
        for (ReqRequirementHandler row : rows) {
            RequirementHandlerVO h = new RequirementHandlerVO();
            h.setId(row.getId());
            h.setUserId(row.getUserId());
            h.setUsername(usernameOf(row.getUserId()));
            h.setPositionId(row.getPositionId());
            if (row.getPositionId() != null) {
                SysPosition pos = positionMapper.selectById(row.getPositionId());
                h.setRoleLabel(pos != null ? pos.getName() : ("岗位#" + row.getPositionId()));
            } else {
                h.setRoleLabel("负责人");
            }
            h.setApplicant(Objects.equals(row.getUserId(), entity.getCreatedBy()));
            h.setCurrentHandler(Objects.equals(row.getUserId(), entity.getCurrentHandlerId()));
            list.add(h);
        }
        return list;
    }

    private ReqRequirement requireInTenant(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        ReqRequirement entity = requirementMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getTenantId(), tenantId)) {
            throw new BizException(404, "需求不存在");
        }
        return entity;
    }

    private RequirementVO toVO(ReqRequirement entity, boolean withFlows) {
        RequirementVO vo = new RequirementVO();
        vo.setId(entity.getId());
        vo.setReqNo(entity.getReqNo());
        vo.setName(entity.getName());
        vo.setProductId(entity.getProductId());
        vo.setProductVersionId(entity.getProductVersionId());
        vo.setProductVersion(entity.getProductVersion());
        vo.setDescription(entity.getDescription());
        RequirementStatus status = RequirementStatus.fromCode(entity.getStatus());
        vo.setStatus(status.name());
        vo.setStatusLabel(status.getLabel());
        vo.setApproverId(entity.getApproverId());
        vo.setApproverUsername(usernameOf(entity.getApproverId()));
        vo.setCurrentHandlerId(entity.getCurrentHandlerId());
        vo.setCurrentHandlerUsername(usernameOf(entity.getCurrentHandlerId()));
        vo.setRejectReason(entity.getRejectReason());
        vo.setPlanDate(entity.getPlanDate());
        vo.setReleaseVersion(entity.getReleaseVersion());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedByUsername(usernameOf(entity.getCreatedBy()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        try {
            SysProduct product = productService.requireOwned(entity.getProductId());
            vo.setProductName(product.getName());
        } catch (BizException ignored) {
            vo.setProductName("-");
        }
        vo.setHandlers(listHandlers(entity));
        if (withFlows) {
            vo.setFlows(listFlows(entity.getId()));
        }
        return vo;
    }

    private List<RequirementFlowVO> listFlows(Long requirementId) {
        return flowMapper.selectList(new LambdaQueryWrapper<ReqRequirementFlow>()
                        .eq(ReqRequirementFlow::getRequirementId, requirementId)
                        .orderByAsc(ReqRequirementFlow::getId))
                .stream()
                .map(this::toFlowVO)
                .toList();
    }

    private RequirementFlowVO toFlowVO(ReqRequirementFlow flow) {
        RequirementFlowVO vo = new RequirementFlowVO();
        vo.setId(flow.getId());
        vo.setNodeType(flow.getNodeType());
        try {
            vo.setNodeTypeLabel(RequirementFlowNodeType.valueOf(flow.getNodeType()).getLabel());
        } catch (Exception e) {
            vo.setNodeTypeLabel(flow.getNodeType());
        }
        vo.setFromStatus(flow.getFromStatus());
        vo.setFromStatusLabel(labelOfStatus(flow.getFromStatus()));
        vo.setToStatus(flow.getToStatus());
        vo.setToStatusLabel(labelOfStatus(flow.getToStatus()));
        vo.setOperatorId(flow.getOperatorId());
        vo.setOperatorUsername(usernameOf(flow.getOperatorId()));
        vo.setCommentText(flow.getCommentText());
        vo.setExtraJson(flow.getExtraJson());
        vo.setCreatedAt(flow.getCreatedAt());
        return vo;
    }

    private String labelOfStatus(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        try {
            return RequirementStatus.fromCode(code).getLabel();
        } catch (Exception e) {
            return code;
        }
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }

    private String writeJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
