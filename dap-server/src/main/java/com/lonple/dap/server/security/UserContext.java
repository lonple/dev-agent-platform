package com.lonple.dap.server.security;

public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new com.lonple.dap.server.common.BizException(401, "未登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
