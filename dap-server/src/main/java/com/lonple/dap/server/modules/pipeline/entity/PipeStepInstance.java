package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_step_instance")
public class PipeStepInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private String stepCode;
    private String stepName;
    private String status;
    private Integer round;
    private String executor;
    private Long handlerUserId;
    private Long sessionId;
    private Long outputArtifactId;
    private Long reviewId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
