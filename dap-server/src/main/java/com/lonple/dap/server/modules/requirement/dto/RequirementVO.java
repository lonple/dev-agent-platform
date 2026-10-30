package com.lonple.dap.server.modules.requirement.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RequirementVO {

    private Long id;
    private String reqNo;
    private String name;
    private Long productId;
    private String productName;
    private Long productVersionId;
    private String productVersion;
    private String description;
    private String status;
    private String statusLabel;
    private Long approverId;
    private String approverUsername;
    private Long currentHandlerId;
    private String currentHandlerUsername;
    private String rejectReason;
    private LocalDate planDate;
    private String releaseVersion;
    private Long createdBy;
    private String createdByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<RequirementHandlerVO> handlers = new ArrayList<>();
    private List<RequirementFlowVO> flows = new ArrayList<>();
}
