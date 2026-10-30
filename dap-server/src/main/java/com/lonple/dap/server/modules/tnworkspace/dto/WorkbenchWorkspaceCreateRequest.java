package com.lonple.dap.server.modules.tnworkspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkbenchWorkspaceCreateRequest {

    /** 工作空间名称：英文、数字、下划线；同时作为磁盘目录名基础 */
    @NotBlank(message = "请输入名称")
    @Size(max = 64, message = "名称不能超过 64 字")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "名称仅支持英文、数字、下划线")
    private String name;
}
