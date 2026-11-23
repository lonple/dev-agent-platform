package com.lonple.dap.server.modules.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class IssueSaveRequest {

    @NotNull
    private Long productId;

    /** 可选关联需求 */
    private Long requirementId;

    @NotBlank
    @Size(max = 20000)
    private String description;

    /** 实现步骤（Markdown，可含图片） */
    @Size(max = 100000)
    private String steps;

    @Size(max = 20000)
    private String expectedResult;

    @Size(max = 20000)
    private String solution;

    /** OPEN / IN_PROGRESS / DONE，默认 OPEN */
    @Size(max = 32)
    private String status;

    private Long handlerId;
}
