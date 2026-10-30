package com.lonple.dap.server.modules.product;

import com.lonple.dap.server.common.BizException;

import java.util.Arrays;

public enum ProductVersionStatus {
    PLANNING("计划中"),
    RELEASED("已发布"),
    INVALID("无效");

    private final String label;

    ProductVersionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ProductVersionStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return PLANNING;
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException("无效的版本状态：" + code));
    }
}
