package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PipelineTrackMergeVO {
    private Long id;
    private String trackCode;
    private String label;
    private String reviewNodeCode;
    private String mergeFileName;
    private String status;
    private String llmMergeJson;
    private String itemsJson;
    private Long mergedWikiNodeId;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
}
