package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PipelineInstanceVO {
    private Long instanceId;
    private Long requirementId;
    private String definitionCode;
    private Integer definitionVersion;
    private String status;
    private String waitingReason;
    private List<String> currentStepCodes;
    private Long totalDurationMs;
    private Long totalPromptTokens;
    private Long totalCompletionTokens;
    private Long totalTokens;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<PipelineStepVO> steps;
}
