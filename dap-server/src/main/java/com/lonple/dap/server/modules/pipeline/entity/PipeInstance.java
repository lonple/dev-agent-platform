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
    private Long tenantId;
    private Long requirementId;
    /** 问题单主体（与 requirementId 二选一） */
    private Long issueId;
    private Long definitionId;
    private Long startedBy;
    private String status;
    private String waitingReason;
    private String optionsJson;
    private Long releaseVersionId;
    private LocalDateTime releaseTime;
    private Long totalDurationMs;
    private Long totalTokens;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
