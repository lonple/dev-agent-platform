package com.lonple.dap.server.modules.review.task.dto;

import com.lonple.dap.server.modules.review.review.model.ReviewResult;
import com.lonple.dap.server.modules.review.task.TaskConfig;
import lombok.Data;

@Data
public class TaskDetailVo {
    private TaskConfig config;
    private ReviewResult result;
}
