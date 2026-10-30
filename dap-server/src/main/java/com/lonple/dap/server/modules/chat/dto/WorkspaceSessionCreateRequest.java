package com.lonple.dap.server.modules.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorkspaceSessionCreateRequest {

    /** NORMAL / TASK */
    @NotBlank
    private String sessionType;

    @NotBlank
    @Size(max = 128)
    private String title;

    /** 任务会话必填 */
    private Long requirementId;

    /** SessionTaskType.name，任务会话必填 */
    private String taskTypeCode;

    /** 岗位，任务会话必填；未传则按任务类型默认岗位 code 解析 */
    private Long positionId;

    /** 个人空间内输出目录，null=根 */
    private Long outputDirNodeId;

    /**
     * 输出参考模板：本租户 Wiki「模板」目录下的 Markdown 文件节点。
     * 任务会话可选；写入交付物时供 LLM 参考结构与写法。
     */
    private Long outputTemplateWikiNodeId;

    /**
     * 规范：本租户 Wiki「规范」目录、子目录或其中文件。
     * 任务会话可选；选目录则展开其下 Markdown，选文件则仅该文件。
     */
    private Long standardsWikiNodeId;

    @Valid
    @Size(max = 50)
    private List<SessionContextRef> contexts = new ArrayList<>();

    /** 任务会话输入文件（仅 Wiki FILE） */
    @Valid
    @Size(max = 100)
    private List<SessionInputRef> inputs = new ArrayList<>();

    /**
     * 代码仓库本地绝对路径（可多个）。编码/集成测试任务必填；其他任务可选。
     */
    @Size(max = 20)
    private List<String> codeRepos = new ArrayList<>();

    @Data
    public static class SessionContextRef {
        @NotNull
        private Long wikiNodeId;
    }

    @Data
    public static class SessionInputRef {
        @NotNull
        private Long wikiNodeId;
    }
}
