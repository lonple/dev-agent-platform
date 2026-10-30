package com.lonple.dap.server.modules.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.modules.role.mapper.RoleMapper;
import com.lonple.dap.server.modules.sysuser.dto.UserVO;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.dto.TenantSaveRequest;
import com.lonple.dap.server.modules.tenant.dto.TenantVO;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import com.lonple.dap.server.modules.tenant.mapper.TenantMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceFsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantUserMapper tenantUserMapper;
    private final SysUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final WorkspaceFsService workspaceFsService;

    public List<TenantVO> list() {
        return tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>().orderByAsc(SysTenant::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    public TenantVO get(Long id) {
        return toVO(requireTenant(id));
    }

    @Transactional
    public Long create(TenantSaveRequest req) {
        assertCodeUnique(req.getCode(), null);
        assertStatus(req.getStatus());
        List<Long> adminIds = normalizeAdminIds(req.getAdminUserIds());

        SysTenant tenant = new SysTenant();
        tenant.setCode(req.getCode());
        tenant.setName(req.getName());
        tenant.setDescription(req.getDescription());
        tenant.setStatus(req.getStatus());
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        replaceAdmins(tenant.getId(), adminIds);
        return tenant.getId();
    }

    @Transactional
    public void update(Long id, TenantSaveRequest req) {
        SysTenant tenant = requireTenant(id);
        assertStatus(req.getStatus());
        if (!Objects.equals(tenant.getCode(), req.getCode())) {
            throw new BizException("租户编码不可修改");
        }
        List<Long> adminIds = normalizeAdminIds(req.getAdminUserIds());
        tenant.setName(req.getName());
        tenant.setDescription(req.getDescription());
        tenant.setStatus(req.getStatus());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        replaceAdmins(id, adminIds);
    }

    @Transactional
    public void delete(Long id) {
        SysTenant tenant = requireTenant(id);
        String tenantCode = tenant.getCode();
        // 按依赖顺序清理租户业务数据（平台用户账号保留）
        tenantMapper.deleteRequirementsByTenantId(id); // cascade: flow / handler；node.requirement_id SET NULL
        tenantMapper.deleteRequirementNoSeqByTenantId(id);
        tenantMapper.deleteProductsByTenantId(id); // cascade: product_version
        tenantMapper.deleteWorkspaceNodesByTenantId(id);
        tenantMapper.deleteWorkspacesByTenantId(id);
        tenantMapper.deleteChatSessionsByTenantId(id); // cascade: chat_message
        tenantMapper.deleteUserTenantContextsByTenantId(id); // cascade: context_skill
        tenantMapper.deletePositionSkillsByTenantId(id);
        tenantMapper.deletePositionsByTenantId(id);
        tenantMapper.deletePromptsByTenantId(id);
        tenantMapper.deleteTenantUsersByTenantId(id);
        tenantMapper.deleteById(id);
        workspaceFsService.deleteTenantDataDir(tenantCode);
    }

    private void replaceAdmins(Long tenantId, List<Long> adminIds) {
        // 原管理员降为操作人员，再将选定用户设为租户管理员
        tenantUserMapper.resetAllToOperator(tenantId);
        for (Long userId : adminIds) {
            tenantUserMapper.upsertMember(tenantId, userId, TenantRole.ADMIN.name());
        }
    }

    private List<Long> normalizeAdminIds(List<Long> adminUserIds) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long userId : adminUserIds) {
            if (userId == null) {
                continue;
            }
            SysUser user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException("租户管理员不存在: " + userId);
            }
            ids.add(userId);
        }
        if (ids.isEmpty()) {
            throw new BizException("请至少配置一名租户管理员");
        }
        return List.copyOf(ids);
    }

    private TenantVO toVO(SysTenant tenant) {
        Map<Long, String> roleNames = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getName, (a, b) -> a));
        TenantVO vo = new TenantVO();
        vo.setId(tenant.getId());
        vo.setCode(tenant.getCode());
        vo.setName(tenant.getName());
        vo.setDescription(tenant.getDescription());
        vo.setStatus(tenant.getStatus());
        vo.setCreatedAt(tenant.getCreatedAt());
        vo.setUpdatedAt(tenant.getUpdatedAt());
        vo.setAdmins(tenantUserMapper.selectAdminsByTenantId(tenant.getId()).stream()
                .map(u -> UserVO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .description(u.getDescription())
                        .roleId(u.getRoleId())
                        .roleName(roleNames.get(u.getRoleId()))
                        .builtin(Boolean.TRUE.equals(u.getBuiltin()))
                        .tenantRole(TenantRole.ADMIN.name())
                        .tenantRoleLabel(TenantRole.ADMIN.getLabel())
                        .tenantAdmin(true)
                        .build())
                .toList());
        return vo;
    }

    private SysTenant requireTenant(Long id) {
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException("租户不存在");
        }
        return tenant;
    }

    private void assertCodeUnique(String code, Long excludeId) {
        LambdaQueryWrapper<SysTenant> qw = new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getCode, code);
        if (excludeId != null) {
            qw.ne(SysTenant::getId, excludeId);
        }
        Long count = tenantMapper.selectCount(qw);
        if (count != null && count > 0) {
            throw new BizException("租户编码已存在");
        }
    }

    private void assertStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态只能是 0 或 1");
        }
    }
}
