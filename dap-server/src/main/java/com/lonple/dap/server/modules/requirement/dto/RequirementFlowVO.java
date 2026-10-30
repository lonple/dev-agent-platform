package com.lonple.dap.server.modules.requirement.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RequirementFlowVO {

    private Long id;
    private String nodeType;
    private String nodeTypeLabel;
    private String fromStatus;
    private String fromStatusLabel;
    private String toStatus;
    private String toStatusLabel;
    private Long operatorId;
    private String operatorUsername;
    private String commentText;
    private String extraJson;
    private LocalDateTime createdAt;
}
