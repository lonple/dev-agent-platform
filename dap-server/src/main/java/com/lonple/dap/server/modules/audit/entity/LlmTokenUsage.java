package com.lonple.dap.server.modules.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("llm_token_usage")
public class LlmTokenUsage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long sessionId;
    private Long messageId;
    private String runId;
    private Long requirementId;
    private Long issueId;
    private String taskTypeCode;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
