package com.lonple.dap.server.modules.tnworkspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkspaceCreateNodeRequest {

    /** 父目录节点 ID，空则在工作空间根下创建 */
    private Long parentId;

    @NotBlank(message = "请输入名称")
    @Size(max = 200, message = "名称不能超过 200 字")
    private String displayName;

    /** 仅 FILE 有效；默认可为空 */
    private String content;
}
