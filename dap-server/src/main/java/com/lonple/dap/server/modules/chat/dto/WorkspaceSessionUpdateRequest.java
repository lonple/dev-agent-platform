package com.lonple.dap.server.modules.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorkspaceSessionUpdateRequest {

    @NotBlank
    @Size(max = 128)
    private String title;

    /** 可改；null 表示根目录 */
    private Long outputDirNodeId;

    /** 输出参考模板：Wiki「模板」下文件；null 表示清除 */
    private Long outputTemplateWikiNodeId;

    /** 规范：Wiki「规范」下目录；null 表示清除 */
    private Long standardsWikiNodeId;

    @Valid
    @Size(max = 50)
    private List<WorkspaceSessionCreateRequest.SessionContextRef> contexts = new ArrayList<>();

    @Valid
    @Size(max = 100)
    private List<WorkspaceSessionCreateRequest.SessionInputRef> inputs = new ArrayList<>();

    /** 代码仓库本地路径列表；空列表表示清除 */
    @Size(max = 20)
    private List<String> codeRepos = new ArrayList<>();
}
