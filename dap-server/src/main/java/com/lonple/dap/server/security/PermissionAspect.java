package com.lonple.dap.server.security;

import com.lonple.dap.server.common.BizException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    @Before("@annotation(requirePermission)")
    public void check(RequirePermission requirePermission) {
        LoginUser user = UserContext.require();
        String code = requirePermission.value();
        if (!user.getPermissions().contains(code)) {
            throw new BizException(403, "无权限：" + code);
        }
    }
}
