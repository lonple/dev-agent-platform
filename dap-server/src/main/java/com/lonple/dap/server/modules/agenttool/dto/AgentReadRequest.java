package com.lonple.dap.server.modules.agenttool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgentReadRequest {
    @NotBlank
    private String source;
    @NotNull
    private Long nodeId;
    private Integer startLine;
    private Integer endLine;
}
