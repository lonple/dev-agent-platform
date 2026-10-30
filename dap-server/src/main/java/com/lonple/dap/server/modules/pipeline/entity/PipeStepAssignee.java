package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_step_assignee")
public class PipeStepAssignee {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stepInstanceId;
    private Long userId;
    private String status;
    private LocalDateTime actedAt;
    private String commentText;
}
