package com.lonple.dap.server.modules.audit.dto;

import lombok.Data;

@Data
public class TokenUsageStatVO {
    private Long userId;
    private String username;
    private Long requirementId;
    private String requirementNo;
    private String requirementName;
    private Long issueId;
    private String issueNo;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
}
