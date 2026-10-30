package com.lonple.dap.server.security;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.modules.role.mapper.RoleMapper;
import com.lonple.dap.server.modules.role.mapper.RolePermissionMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.TenantRole;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_TENANT = "X-Tenant-Id";

    private final JwtService jwtService;
    private final SysUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final TenantUserMapper tenantUserMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (!StringUtils.hasText(auth) || !auth.startsWith("Bearer ")) {
            throw new BizException(401, "未登录");
        }
        String token = auth.substring(7);
        Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (Exception e) {
            throw new BizException(401, "登录已失效");
        }
        Long userId = Long.valueOf(claims.getSubject());
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        SysRole role = roleMapper.selectById(user.getRoleId());
        List<String> codes = rolePermissionMapper.selectCodesByRoleId(user.getRoleId());
        HashSet<String> permissions = new HashSet<>(codes);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setBuiltin(Boolean.TRUE.equals(user.getBuiltin()));
        loginUser.setRoleId(user.getRoleId());
        loginUser.setRoleName(role != null ? role.getName() : null);

        String tenantHeader = request.getHeader(HEADER_TENANT);
        if (StringUtils.hasText(tenantHeader)) {
            try {
                Long tenantId = Long.valueOf(tenantHeader);
                if (tenantUserMapper.existsMembership(tenantId, userId)) {
                    loginUser.setCurrentTenantId(tenantId);
                    String tenantRoleCode = tenantUserMapper.selectTenantRole(tenantId, userId);
                    if (tenantRoleCode != null) {
                        permissions.addAll(TenantRole.fromCode(tenantRoleCode).permissions());
                    }
                }
            } catch (NumberFormatException ignored) {
                // ignore invalid tenant header for user-center
            }
        }
        loginUser.setPermissions(permissions);
        UserContext.set(loginUser);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
