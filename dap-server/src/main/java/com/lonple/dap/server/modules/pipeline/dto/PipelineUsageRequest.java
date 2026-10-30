package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

@Data
public class PipelineUsageRequest {
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
