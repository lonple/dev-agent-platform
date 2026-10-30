package com.lonple.dap.server.modules.requirement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("req_requirement")
public class ReqRequirement {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String reqNo;
    private String name;
    private Long productId;
    private Long productVersionId;
    private String productVersion;
    private String description;
    private String status;
    private Long approverId;
    private Long currentHandlerId;
    private String rejectReason;
    private LocalDate planDate;
    private String releaseVersion;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
