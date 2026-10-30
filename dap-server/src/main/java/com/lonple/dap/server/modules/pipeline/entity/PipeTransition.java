package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pipe_transition")
public class PipeTransition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private String fromStepCode;
    private String toStepCode;
    private String event;
    private Integer priority;
}
