package com.lonple.dap.server.modules.tnuser;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.modules.role.mapper.RoleMapper;
import com.lonple.dap.server.modules.sysuser.dto.UserVO;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.TenantRole;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.modules.tnuser.dto.TenantUserSaveRequest;
import com.lonple.dap.server.modules.tnuser.dto.TenantUserUpdateRequest;
import com.lonple.dap.server.security.TenantSupport;
import com.lonple.dap.server.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantUserService {

    private final SysUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final TenantUserMapper tenantUserMapper;

    public List<UserVO> list() {
        Long tenantId = TenantSupport.requireTenantId();
        Map<Long, String> roleNames = roleNames();
        return tenantUserMapper.selectUsersByTenantId(tenantId).stream()
                .map(u -> toVo(u, roleNames.get(u.getRoleId()), tenantId))
                .toList();
    }

    /** 用户中心用户中尚未加入当前租户的候选人 */
    public List<UserVO> listCandidates() {
        Long tenantId = TenantSupport.requireTenantId();
        Set<Long> memberIds = tenantUserMapper.selectUsersByTenantId(tenantId).stream()
                .map(SysUser::getId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, String> roleNames = roleNames();
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId)).stream()
                .filter(u -> !memberIds.contains(u.getId()))
                .map(u -> UserVO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .description(u.getDescription())
                        .roleId(u.getRoleId())
                        .roleName(roleNames.get(u.getRoleId()))
                        .builtin(Boolean.TRUE.equals(u.getBuiltin()))
                        .build())
                .toList();
    }

    public List<Map<String, String>> listTenantRoles() {
        return Arrays.stream(TenantRole.values())
                .map(r -> Map.of("code", r.name(), "label", r.getLabel()))
                .toList();
    }

    @Transactional
    public Long add(TenantUserSaveRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        TenantRole tenantRole = TenantRole.fromCode(req.getTenantRole());
        SysUser user = userMapper.selectById(req.getUserId());
        if (user == null) {
            throw new BizException("用户不存在，请先在用户中心创建");
        }
        if (tenantUserMapper.selectTenantRole(tenantId, user.getId()) != null) {
            throw new BizException("该用户已在当前租户中");
        }
        tenantUserMapper.upsertMember(tenantId, user.getId(), tenantRole.name());
        return user.getId();
    }

    @Transactional
    public void update(Long userId, TenantUserUpdateRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        requireTenantMember(tenantId, userId);
        TenantRole tenantRole = TenantRole.fromCode(req.getTenantRole());
        if (tenantRole == TenantRole.OPERATOR) {
            TenantRole current = TenantRole.fromCode(tenantUserMapper.selectTenantRole(tenantId, userId));
            if (current == TenantRole.ADMIN) {
                throw new BizException("租户管理员角色请在用户中心的租户管理中调整");
            }
        }
        tenantUserMapper.upsertMember(tenantId, userId, tenantRole.name());
    }

    @Transactional
    public void remove(Long userId) {
        Long tenantId = TenantSupport.requireTenantId();
        requireTenantMember(tenantId, userId);
        if (Objects.equals(UserContext.require().getUserId(), userId)) {
            throw new BizException("不能移除当前登录用户");
        }
        // 仅移除租户成员关系（含管理员），不删除用户中心账号
        tenantUserMapper.deleteMember(tenantId, userId);
    }

    private SysUser requireTenantMember(Long tenantId, Long userId) {
        if (tenantUserMapper.selectTenantRole(tenantId, userId) == null) {
            throw new BizException("用户不属于当前租户");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    private Map<Long, String> roleNames() {
        return roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getName, (a, b) -> a));
    }

    private UserVO toVo(SysUser user, String platformRoleName, Long tenantId) {
        TenantRole tenantRole = TenantRole.fromCode(tenantUserMapper.selectTenantRole(tenantId, user.getId()));
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .description(user.getDescription())
                .roleId(user.getRoleId())
                .roleName(platformRoleName)
                .builtin(Boolean.TRUE.equals(user.getBuiltin()))
                .tenantRole(tenantRole.name())
                .tenantRoleLabel(tenantRole.getLabel())
                .tenantAdmin(tenantRole == TenantRole.ADMIN)
                .build();
    }
}
