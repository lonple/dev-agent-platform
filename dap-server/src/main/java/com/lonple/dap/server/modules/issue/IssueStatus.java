package com.lonple.dap.server.modules.issue;

import com.lonple.dap.server.common.BizException;

import java.util.Arrays;
import java.util.Set;

public enum IssueStatus {
    OPEN("未处理"),
    IN_PROGRESS("处理中"),
    DONE("完成");

    /** 工作台待办：未处理 / 处理中 */
    public static final Set<String> TODO_STATUSES = Set.of(OPEN.name(), IN_PROGRESS.name());

    /** 工作台全部：含完成 */
    public static final Set<String> WORKBENCH_ALL_STATUSES = Set.of(
            OPEN.name(), IN_PROGRESS.name(), DONE.name());

    private final String label;

    IssueStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static IssueStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("问题单状态不能为空");
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException("无效的问题单状态：" + code));
    }
}
