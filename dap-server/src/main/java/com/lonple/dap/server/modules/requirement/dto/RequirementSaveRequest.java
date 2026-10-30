package com.lonple.dap.server.modules.requirement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RequirementSaveRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotNull
    private Long productId;

    @NotNull
    private Long productVersionId;

    @NotBlank
    @Size(max = 20000)
    private String description;
}
