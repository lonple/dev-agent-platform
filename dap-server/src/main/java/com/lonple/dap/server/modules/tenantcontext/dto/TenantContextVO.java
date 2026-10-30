package com.lonple.dap.server.modules.tenantcontext.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TenantContextVO {

    private Long positionId;
    private String positionName;
    private List<Long> skillIds = new ArrayList<>();
    private List<String> skillNames = new ArrayList<>();
    private String customSkillText;
}
