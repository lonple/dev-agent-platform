package com.lonple.dap.server.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatStopRequest {

    @NotBlank
    private String runId;
}
