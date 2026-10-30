package com.lonple.dap.server.modules.agenttool.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentFileIndexVO {
    private Long sessionId;
    private String sessionType;
    private String title;
    private Long workspaceId;
    private Long outputDirNodeId;
    private String outputDirLabel;
    /** 流水线：Wiki 输出目录 */
    private Long outputWikiDirNodeId;
    private String outputWikiDirPath;
    private String taskTypeCode;
    private String taskTypeName;
    private String taskRemark;
    private Boolean requireDeliverable;
    /**
     * 交付/输出参考模板正文。
     * 优先取会话绑定的 Wiki 模板文件内容，否则回退任务类型上的 deliverableTemplate。
     */
    private String deliverableTemplate;
    /** 会话绑定的输出参考模板 Wiki 路径（有则说明模板来自知识库） */
    private Long outputTemplateWikiNodeId;
    private String outputTemplatePath;
    /** 会话绑定的规范（Wiki「规范」下 DIR/FILE） */
    private Long standardsWikiNodeId;
    private String standardsPath;
    /**
     * 规范正文（单文件或目录下多文件拼接）。
     * 交付物撰写时必须遵守其中约束；与输出参考模板（结构）互补。
     */
    private String standardsContent;
    /** 编码任务绑定的本地仓库绝对路径 */
    private List<String> codeRepos = new ArrayList<>();
    private List<Entry> files = new ArrayList<>();

    @Data
    public static class Entry {
        /** wiki | workspace */
        private String source;
        private Long nodeId;
        private String displayPath;
        /** input | context | output | standards */
        private String role;
        private Integer approxChars;
    }
}
