package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

@Data
public class PipelineReviewDocVO {
    private Long reviewId;
    private Long stepInstanceId;
    private String stepName;
    private String roleHint;
    private Long wikiNodeId;
    private String wikiPath;
}
