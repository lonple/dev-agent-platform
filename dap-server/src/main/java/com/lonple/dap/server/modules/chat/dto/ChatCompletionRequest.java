package com.lonple.dap.server.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatCompletionRequest {

    @NotNull(message = "请指定会话")
    private Long sessionId;

    @Size(max = 64)
    private String clientTempId;

    @NotBlank
    @Size(max = 20000)
    private String content;
}
