package com.lonple.dap.server.modules.tnworkspace;

import com.lonple.dap.server.common.BizException;

import java.util.Arrays;

public enum WorkspaceSpaceType {
    SHARED("共享"),
    PERSONAL("个人");

    private final String label;

    WorkspaceSpaceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static WorkspaceSpaceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return SHARED;
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException("无效的工作空间类型：" + code));
    }
}
