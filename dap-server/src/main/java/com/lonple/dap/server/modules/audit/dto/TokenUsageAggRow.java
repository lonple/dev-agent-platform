package com.lonple.dap.server.modules.audit.dto;

import lombok.Data;

@Data
public class TokenUsageAggRow {
    private Long userId;
    private Long requirementId;
    private Long issueId;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
