package com.lonple.dap.server.modules.requirement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RequirementPlanRequest {

    @NotNull
    private Long releaseVersionId;

    @Size(max = 1000)
    private String comment;
}
