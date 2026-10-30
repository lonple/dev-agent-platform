package com.lonple.dap.server.modules.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TenantSaveRequest {

    @NotBlank(message = "租户编码不能为空")
    @Size(max = 64, message = "租户编码不能超过 64 字")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "租户编码仅支持字母数字下划线")
    private String code;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 64, message = "租户名称不能超过 64 字")
    private String name;

    @Size(max = 200, message = "描述不能超过 200 字")
    private String description;

    @NotNull(message = "状态不能为空")
    private Integer status;

    /** 租户管理员，可配置多名 */
    @NotEmpty(message = "请至少配置一名租户管理员")
    private List<Long> adminUserIds;
}
