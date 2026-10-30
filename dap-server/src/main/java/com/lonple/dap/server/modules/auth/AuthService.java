package com.lonple.dap.server.modules.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.auth.dto.LoginRequest;
import com.lonple.dap.server.modules.auth.dto.LoginResponse;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.modules.role.mapper.RoleMapper;
import com.lonple.dap.server.modules.role.mapper.RolePermissionMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import com.lonple.dap.server.modules.tenant.mapper.TenantMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.modules.tenant.TenantRole;
import com.lonple.dap.server.security.JwtService;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final TenantUserMapper tenantUserMapper;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return buildLoginResponse(user, jwtService.createToken(user.getId(), user.getUsername()), null);
    }

    public LoginResponse me() {
        LoginUser loginUser = UserContext.require();
        SysUser user = userMapper.selectById(loginUser.getUserId());
        return buildLoginResponse(user, null, loginUser.getCurrentTenantId());
    }

    public List<SysTenant> myTenants() {
        LoginUser loginUser = UserContext.require();
        return tenantUserMapper.selectEnabledTenantsByUserId(loginUser.getUserId());
    }

    private LoginResponse buildLoginResponse(SysUser user, String token, Long currentTenantId) {
        SysRole role = roleMapper.selectById(user.getRoleId());
        HashSet<String> permissions = new HashSet<>(rolePermissionMapper.selectCodesByRoleId(user.getRoleId()));
        String tenantName = null;
        Long resolvedTenantId = currentTenantId;
        if (resolvedTenantId != null) {
            SysTenant tenant = tenantMapper.selectById(resolvedTenantId);
            if (tenant != null && tenant.getStatus() != null && tenant.getStatus() == 1
                    && tenantUserMapper.existsMembership(resolvedTenantId, user.getId())) {
                tenantName = tenant.getName();
                String tenantRoleCode = tenantUserMapper.selectTenantRole(resolvedTenantId, user.getId());
                if (tenantRoleCode != null) {
                    permissions.addAll(TenantRole.fromCode(tenantRoleCode).permissions());
                }
            } else {
                resolvedTenantId = null;
            }
        }
        return LoginResponse.builder()
                .token(token)
                .user(LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .description(user.getDescription())
                        .roleId(user.getRoleId())
                        .roleName(role != null ? role.getName() : null)
                        .builtin(Boolean.TRUE.equals(user.getBuiltin()))
                        .build())
                .permissions(permissions)
                .currentTenantId(resolvedTenantId)
                .currentTenantName(tenantName)
                .build();
    }
}
