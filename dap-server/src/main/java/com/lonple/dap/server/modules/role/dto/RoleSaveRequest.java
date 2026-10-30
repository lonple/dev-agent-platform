package com.lonple.dap.server.modules.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RoleSaveRequest {

    @NotBlank(message = "角色名不能为空")
    @Size(max = 64, message = "角色名不能超过 64 字")
    private String name;

    @Size(max = 200, message = "描述不能超过 200 字")
    private String description;
}
