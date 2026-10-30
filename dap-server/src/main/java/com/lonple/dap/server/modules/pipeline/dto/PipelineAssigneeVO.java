package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PipelineAssigneeVO {
    private Long userId;
    private String username;
    private String status;
    private LocalDateTime actedAt;
}
