package com.lonple.dap.server.security;

import com.lonple.dap.server.common.BizException;

public final class TenantSupport {

    private TenantSupport() {
    }

    public static Long requireTenantId() {
        LoginUser user = UserContext.require();
        Long tenantId = user.getCurrentTenantId();
        if (tenantId == null) {
            throw new BizException(400, "请先选择租户");
        }
        return tenantId;
    }

    public static Long requireUserId() {
        return UserContext.require().getUserId();
    }
}
