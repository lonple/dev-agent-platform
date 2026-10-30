package com.lonple.dap.server.modules.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductVersionSaveRequest {

    @NotBlank
    @Size(max = 64)
    private String version;

    @NotBlank
    @Size(max = 32)
    private String status;

    /** 计划中状态必填 */
    private LocalDateTime planReleaseAt;

    @Size(max = 500)
    private String description;
}
