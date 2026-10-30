package com.lonple.dap.server.modules.position.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PositionSaveRequest {

    @Size(max = 32)
    private String code;

    @NotBlank
    @Size(max = 64)
    private String name;

    @Size(max = 200)
    private String description;
}
