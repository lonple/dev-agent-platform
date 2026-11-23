package com.lonple.dap.server.modules.review.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTaskRequest {
    @NotBlank
    private String name;
}
