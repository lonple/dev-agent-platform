package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

@Data
public class PipelineReviewItemVO {
    private Long id;
    private Integer ordinal;
    private String content;
    private Boolean checked;
    private String source;
}
