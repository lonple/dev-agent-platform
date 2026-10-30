package com.lonple.dap.server.modules.requirement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("req_requirement_handler")
public class ReqRequirementHandler {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long requirementId;
    private Long userId;
    /** 租户岗位；默认负责人可为空 */
    private Long positionId;
    private LocalDateTime createdAt;
}
