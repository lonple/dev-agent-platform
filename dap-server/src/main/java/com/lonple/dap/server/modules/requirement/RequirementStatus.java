package com.lonple.dap.server.modules.requirement;

import com.lonple.dap.server.common.BizException;

import java.util.Arrays;
import java.util.Set;

public enum RequirementStatus {
    DRAFT("草稿"),
    APPROVING("审批中"),
    REJECTED("驳回"),
    PENDING_PLAN("待计划"),
    PLANNING("计划中"),
    DEVELOPING("研发中"),
    RELEASED("完成");

    /** 工作台待办：非草稿 / 驳回 / 完成 */
    public static final Set<String> TODO_STATUSES = Set.of(
            APPROVING.name(),
            PENDING_PLAN.name(),
            PLANNING.name(),
            DEVELOPING.name());

    /** 工作台全部：非草稿 / 驳回（含完成） */
    public static final Set<String> WORKBENCH_ALL_STATUSES = Set.of(
            APPROVING.name(),
            PENDING_PLAN.name(),
            PLANNING.name(),
            DEVELOPING.name(),
            RELEASED.name());

    /** @deprecated 使用 TODO_STATUSES */
    public static final Set<String> INBOX_STATUSES = TODO_STATUSES;

    private final String label;

    RequirementStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static RequirementStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return DRAFT;
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException("无效的需求状态：" + code));
    }
}
