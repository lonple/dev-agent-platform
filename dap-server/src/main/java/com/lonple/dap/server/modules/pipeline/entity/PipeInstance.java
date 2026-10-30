package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_instance")
public class PipeInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requirementId;
    private Long definitionId;
    private String status;
    private String waitingReason;
    private String currentStepCodes;
    private Long totalDurationMs;
    private Long totalPromptTokens;
    private Long totalCompletionTokens;
    private Long totalTokens;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
