package com.lonple.dap.server.modules.chat;

import org.springframework.util.StringUtils;

/**
 * 判定任务会话是否需要绑定本地代码仓库，并启用 LLM 代码工具白名单。
 */
public final class CodingTaskSupport {

    private CodingTaskSupport() {}

    public static boolean needsLocalCodeRepo(String taskTypeCode) {
        if (!StringUtils.hasText(taskTypeCode)) {
            return false;
        }
        try {
            return SessionTaskType.fromCode(taskTypeCode).needsLocalCodeRepo();
        } catch (Exception e) {
            return false;
        }
    }
}
