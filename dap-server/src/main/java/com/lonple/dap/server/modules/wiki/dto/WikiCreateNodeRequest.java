package com.lonple.dap.server.modules.wiki.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WikiCreateNodeRequest {

    private Long parentId;

    @NotBlank(message = "请输入名称")
    @Size(max = 200, message = "名称不能超过 200 字")
    private String displayName;

    /** 仅 FILE */
    private String content;
}
