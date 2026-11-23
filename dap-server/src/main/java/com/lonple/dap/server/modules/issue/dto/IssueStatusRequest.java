package com.lonple.dap.server.modules.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class IssueStatusRequest {

    /** OPEN / IN_PROGRESS / DONE */
    @NotBlank
    @Size(max = 32)
    private String status;
}
