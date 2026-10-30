package com.lonple.dap.server.modules.tnuser.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TenantUserUpdateRequest {

    @NotBlank(message = "请选择租户角色")
    private String tenantRole;
}
