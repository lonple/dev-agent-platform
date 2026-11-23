package com.lonple.dap.server.modules.issue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("iss_issue")
public class IssIssue {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String issueNo;
    private Long productId;
    /** 可选关联需求 */
    private Long requirementId;
    private String description;
    private String steps;
    private String expectedResult;
    private String solution;
    private String status;
    private Long handlerId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
