package com.lonple.dap.server.modules.tenantcontext.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TenantContextUpdateRequest {

    private Long positionId;
    private List<Long> skillIds = new ArrayList<>();

    @Size(max = 128)
    private String customSkillText;
}
