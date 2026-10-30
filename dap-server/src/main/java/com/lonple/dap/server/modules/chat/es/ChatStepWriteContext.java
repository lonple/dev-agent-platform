package com.lonple.dap.server.modules.chat.es;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatStepWriteContext {
    private Long tenantId;
    private Long userId;
    private String username;
    private Long workspaceId;
    private Long sessionId;
    private String sessionTitle;
    private Long requirementId;
    private String taskTypeCode;
    private Long positionId;
    private Long messageId;
    private String runId;
    private Long pipeInstanceId;
    private Long pipeStepInstanceId;
    private String pipeStepCode;
}
