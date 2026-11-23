package com.lonple.dap.server.modules.issue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.issue.dto.IssueHandlerCandidateVO;
import com.lonple.dap.server.modules.issue.dto.IssueSaveRequest;
import com.lonple.dap.server.modules.issue.dto.IssueVO;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.issue.mapper.IssueMapper;
import com.lonple.dap.server.modules.issue.mapper.IssueNoSeqMapper;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueMapper issueMapper;
    private final IssueNoSeqMapper issueNoSeqMapper;
    private final ProductService productService;
    private final RequirementMapper requirementMapper;
    private final SysUserMapper userMapper;
    private final TenantUserMapper tenantUserMapper;

    public List<IssueVO> list(String status) {
        Long tenantId = TenantSupport.requireTenantId();
        LambdaQueryWrapper<IssIssue> q = new LambdaQueryWrapper<IssIssue>()
                .eq(IssIssue::getTenantId, tenantId)
                .orderByDesc(IssIssue::getId);
        if (StringUtils.hasText(status)) {
            IssueStatus st = IssueStatus.fromCode(status);
            q.eq(IssIssue::getStatus, st.name());
        }
        return issueMapper.selectList(q).stream().map(this::toVO).toList();
    }

    /**
     * 工作台问题列表：处理人=当前用户，或处理人为空且创建人=当前用户。
     * scope=todo 待办（未处理/处理中）；scope=all 全部（含完成）。
     */
    public List<IssueVO> listWorkbench(String scope) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        Set<String> statuses = isAllScope(scope)
                ? IssueStatus.WORKBENCH_ALL_STATUSES
                : IssueStatus.TODO_STATUSES;
        return issueMapper.selectList(new LambdaQueryWrapper<IssIssue>()
                        .eq(IssIssue::getTenantId, tenantId)
                        .in(IssIssue::getStatus, statuses)
                        .and(w -> w.eq(IssIssue::getHandlerId, userId)
                                .or(x -> x.isNull(IssIssue::getHandlerId).eq(IssIssue::getCreatedBy, userId)))
                        .orderByDesc(IssIssue::getUpdatedAt)
                        .orderByDesc(IssIssue::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private static boolean isAllScope(String scope) {
        return scope != null && "all".equalsIgnoreCase(scope.trim());
    }

    public IssueVO get(Long id) {
        return toVO(requireInTenant(id));
    }

    public IssIssue requireOwned(Long id) {
        return requireInTenant(id);
    }

    public List<IssueHandlerCandidateVO> listHandlerCandidates() {
        Long tenantId = TenantSupport.requireTenantId();
        return tenantUserMapper.selectUsersByTenantId(tenantId).stream().map(u -> {
            IssueHandlerCandidateVO vo = new IssueHandlerCandidateVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            return vo;
        }).toList();
    }

    @Transactional
    public Long create(IssueSaveRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        SysProduct product = productService.requireOwned(req.getProductId());
        IssueStatus status = resolveStatus(req.getStatus(), IssueStatus.OPEN);
        Long handlerId = normalizeHandler(tenantId, req.getHandlerId());
        Long requirementId = normalizeRequirement(tenantId, product.getId(), req.getRequirementId());

        IssIssue entity = new IssIssue();
        entity.setTenantId(tenantId);
        entity.setIssueNo(nextIssueNo(tenantId, product));
        entity.setProductId(product.getId());
        entity.setRequirementId(requirementId);
        entity.setDescription(req.getDescription().trim());
        entity.setSteps(trimOrEmpty(req.getSteps()));
        entity.setExpectedResult(trimOrEmpty(req.getExpectedResult()));
        entity.setSolution(trimOrEmpty(req.getSolution()));
        entity.setStatus(status.name());
        entity.setHandlerId(handlerId);
        entity.setCreatedBy(userId);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        issueMapper.insert(entity);
        return entity.getId();
    }

    @Transactional
    public void update(Long id, IssueSaveRequest req) {
        IssIssue entity = requireInTenant(id);
        SysProduct product = productService.requireOwned(req.getProductId());
        boolean productChanged = !Objects.equals(entity.getProductId(), product.getId());
        IssueStatus status = resolveStatus(req.getStatus(), IssueStatus.fromCode(entity.getStatus()));
        Long handlerId = normalizeHandler(entity.getTenantId(), req.getHandlerId());
        Long requirementId = normalizeRequirement(entity.getTenantId(), product.getId(), req.getRequirementId());

        entity.setProductId(product.getId());
        entity.setRequirementId(requirementId);
        entity.setDescription(req.getDescription().trim());
        entity.setSteps(trimOrEmpty(req.getSteps()));
        entity.setExpectedResult(trimOrEmpty(req.getExpectedResult()));
        entity.setSolution(trimOrEmpty(req.getSolution()));
        entity.setStatus(status.name());
        entity.setHandlerId(handlerId);
        if (productChanged) {
            entity.setIssueNo(nextIssueNo(entity.getTenantId(), product));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(entity);
    }

    @Transactional
    public void delete(Long id) {
        IssIssue entity = requireInTenant(id);
        issueMapper.deleteById(entity.getId());
    }

    /** 状态转换：未处理 / 处理中 / 完成。 */
    @Transactional
    public void changeStatus(Long id, String statusCode) {
        IssIssue entity = requireInTenant(id);
        IssueStatus next = IssueStatus.fromCode(statusCode);
        if (next.name().equals(entity.getStatus())) {
            return;
        }
        entity.setStatus(next.name());
        entity.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(entity);
    }

    private String nextIssueNo(Long tenantId, SysProduct product) {
        if (!StringUtils.hasText(product.getCode())) {
            throw new BizException("产品未配置编码，无法生成问题单编码");
        }
        issueNoSeqMapper.ensureRow(tenantId, product.getId());
        Integer last = issueNoSeqMapper.selectLastSeqForUpdate(tenantId, product.getId());
        if (last == null) {
            throw new BizException("生成问题单编码失败");
        }
        int seq = last + 1;
        issueNoSeqMapper.updateLastSeq(tenantId, product.getId(), seq);
        return product.getCode().trim() + "-" + seq;
    }

    private Long normalizeHandler(Long tenantId, Long handlerId) {
        if (handlerId == null) {
            return null;
        }
        if (!tenantUserMapper.existsMembership(tenantId, handlerId)) {
            throw new BizException("处理人不属于当前租户");
        }
        return handlerId;
    }

    private Long normalizeRequirement(Long tenantId, Long productId, Long requirementId) {
        if (requirementId == null) {
            return null;
        }
        ReqRequirement req = requirementMapper.selectById(requirementId);
        if (req == null || !Objects.equals(req.getTenantId(), tenantId)) {
            throw new BizException("关联需求不存在");
        }
        if (!Objects.equals(req.getProductId(), productId)) {
            throw new BizException("关联需求必须属于所选产品");
        }
        return requirementId;
    }

    private IssueStatus resolveStatus(String code, IssueStatus fallback) {
        if (!StringUtils.hasText(code)) {
            return fallback;
        }
        return IssueStatus.fromCode(code);
    }

    private IssIssue requireInTenant(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        IssIssue entity = issueMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getTenantId(), tenantId)) {
            throw new BizException(404, "问题单不存在");
        }
        return entity;
    }

    private IssueVO toVO(IssIssue entity) {
        IssueVO vo = new IssueVO();
        vo.setId(entity.getId());
        vo.setIssueNo(entity.getIssueNo());
        vo.setProductId(entity.getProductId());
        vo.setRequirementId(entity.getRequirementId());
        vo.setDescription(entity.getDescription());
        vo.setSteps(entity.getSteps());
        vo.setExpectedResult(entity.getExpectedResult());
        vo.setSolution(entity.getSolution());
        vo.setStatus(entity.getStatus());
        IssueStatus status = IssueStatus.fromCode(entity.getStatus());
        vo.setStatusLabel(status.getLabel());
        vo.setHandlerId(entity.getHandlerId());
        vo.setHandlerUsername(usernameOf(entity.getHandlerId()));
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedByUsername(usernameOf(entity.getCreatedBy()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        try {
            SysProduct product = productService.requireOwned(entity.getProductId());
            vo.setProductName(product.getName());
            vo.setProductCode(product.getCode());
        } catch (BizException ignored) {
            // ignore
        }
        if (entity.getRequirementId() != null) {
            ReqRequirement req = requirementMapper.selectById(entity.getRequirementId());
            if (req != null) {
                vo.setRequirementNo(req.getReqNo());
                vo.setRequirementName(req.getName());
            }
        }
        return vo;
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
