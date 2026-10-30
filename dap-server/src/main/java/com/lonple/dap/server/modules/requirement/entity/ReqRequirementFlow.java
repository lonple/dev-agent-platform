package com.lonple.dap.server.modules.requirement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("req_requirement_flow")
public class ReqRequirementFlow {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long requirementId;
    private String nodeType;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String commentText;
    private String extraJson;
    private LocalDateTime createdAt;
}
