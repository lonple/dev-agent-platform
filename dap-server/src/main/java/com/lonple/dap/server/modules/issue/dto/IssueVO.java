package com.lonple.dap.server.modules.issue.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueVO {

    private Long id;
    private String issueNo;
    private Long productId;
    private String productName;
    private String productCode;
    private Long requirementId;
    private String requirementNo;
    private String requirementName;
    private String description;
    private String steps;
    private String expectedResult;
    private String solution;
    private String status;
    private String statusLabel;
    private Long handlerId;
    private String handlerUsername;
    private Long createdBy;
    private String createdByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
