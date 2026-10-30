package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

@Data
public class PipelineAiPrepareVO {
    private Long stepInstanceId;
    private Long sessionId;
    private Long workspaceId;
    private String kickoffPrompt;
    private PipelineInstanceVO instance;
}
