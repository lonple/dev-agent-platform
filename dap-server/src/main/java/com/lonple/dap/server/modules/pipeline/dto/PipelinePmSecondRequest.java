package com.lonple.dap.server.modules.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PipelinePmSecondRequest {
    /** PASS=仅通过挂起；PASS_AND_DEV=投入研发继续 */
    @NotBlank
    private String action;
    @NotNull
    private Long releaseVersionId;
    private LocalDateTime releaseTime;
    /** 是否启用架构设计 */
    private boolean enableArch;
    /** 是否启用 UI 设计 */
    private boolean enableUi;
}
