package com.lonple.dap.server.modules.agenttool.dto;

import lombok.Data;

@Data
public class AgentWriteResultVO {
    private Long workspaceId;
    private Long nodeId;
    private String displayName;
    private String displayPath;
    private Integer versionNo;
    private boolean created;
}
