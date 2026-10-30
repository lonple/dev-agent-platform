package com.lonple.dap.server.modules.agenttool.dto;

import lombok.Data;

@Data
public class AgentReadResultVO {
    private String source;
    private Long nodeId;
    private String displayPath;
    private Integer startLine;
    private Integer endLine;
    private Integer totalLines;
    private String content;
}
