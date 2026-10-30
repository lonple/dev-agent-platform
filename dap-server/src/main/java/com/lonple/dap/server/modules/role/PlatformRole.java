package com.lonple.dap.server.modules.role;

import com.lonple.dap.server.common.BizException;

import java.util.Arrays;

/** 平台角色（sys_role 内置） */
public enum PlatformRole {
    SYSTEM_ADMIN(1L, "系统管理员"),
    SYSTEM_OPERATOR(2L, "系统操作人员");

    private final long id;
    private final String name;

    PlatformRole(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public static boolean isPlatformRoleId(Long roleId) {
        if (roleId == null) {
            return false;
        }
        return Arrays.stream(values()).anyMatch(r -> r.id == roleId);
    }

    public static void assertPlatformRoleId(Long roleId) {
        if (!isPlatformRoleId(roleId)) {
            throw new BizException("请选择平台角色：系统管理员或系统操作人员");
        }
    }
}
