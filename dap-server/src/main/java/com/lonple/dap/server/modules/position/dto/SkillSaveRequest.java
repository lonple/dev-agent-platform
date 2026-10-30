package com.lonple.dap.server.modules.position.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SkillSaveRequest {

    @NotBlank
    @Size(max = 64)
    private String name;

    @Size(max = 200)
    private String description;

    private Integer sortOrder;
}
