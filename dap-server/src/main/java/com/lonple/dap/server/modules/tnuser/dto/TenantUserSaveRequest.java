package com.lonple.dap.server.modules.tnuser.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TenantUserSaveRequest {

    /** 用户中心已有用户 */
    @NotNull(message = "请选择用户")
    private Long userId;

    /** ADMIN / OPERATOR */
    @NotBlank(message = "请选择租户角色")
    private String tenantRole;
}
