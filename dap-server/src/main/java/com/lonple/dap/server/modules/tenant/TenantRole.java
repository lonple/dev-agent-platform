package com.lonple.dap.server.modules.tenant;

import com.lonple.dap.server.common.BizException;

import java.util.Arrays;
import java.util.Set;

public enum TenantRole {
    ADMIN("租户管理员"),
    OPERATOR("租户操作人员");

    public static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "tenant-user:view",
            "tenant-user:operate",
            "position:view",
            "position:operate",
            "product:view",
            "product:operate",
            "prompt:view",
            "prompt:operate",
            "workspace:view",
            "workspace:operate",
            "requirement:view",
            "requirement:operate",
            "issue:view",
            "issue:operate",
            "work:access",
            "audit:view");

    public static final Set<String> OPERATOR_PERMISSIONS = Set.of(
            "requirement:view",
            "requirement:operate",
            "issue:view",
            "issue:operate",
            "work:access");

    private final String label;

    TenantRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public Set<String> permissions() {
        return this == ADMIN ? ADMIN_PERMISSIONS : OPERATOR_PERMISSIONS;
    }

    public static TenantRole fromCode(String code) {
        if (code == null || code.isBlank()) {
            return OPERATOR;
        }
        return Arrays.stream(values())
                .filter(r -> r.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException("无效的租户角色：" + code));
    }
}
