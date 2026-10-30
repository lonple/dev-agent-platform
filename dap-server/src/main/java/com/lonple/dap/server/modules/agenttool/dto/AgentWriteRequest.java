package com.lonple.dap.server.modules.agenttool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentWriteRequest {
    @NotBlank
    private String displayName;
    private String content;
}
