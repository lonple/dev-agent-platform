package com.lonple.dap.server.modules.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptSaveRequest {

    @NotBlank
    @Size(max = 64)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotNull
    private Long positionId;

    /** SessionTaskType.name */
    @NotBlank
    @Size(max = 64)
    private String taskTypeCode;

    /** Markdown 正文，可空（新建后编辑） */
    private String content;
}
