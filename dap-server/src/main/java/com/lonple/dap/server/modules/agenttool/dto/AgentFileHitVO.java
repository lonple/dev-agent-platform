package com.lonple.dap.server.modules.agenttool.dto;

import lombok.Data;

@Data
public class AgentFileHitVO {
    private String source;
    private Long nodeId;
    private String displayPath;
    private Integer line;
    private String snippet;
}
