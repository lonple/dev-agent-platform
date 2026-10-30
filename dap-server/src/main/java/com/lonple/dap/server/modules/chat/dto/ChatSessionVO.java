package com.lonple.dap.server.modules.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatSessionVO {
    private Long id;
    private Long workspaceId;
    private String sessionType;
    private String title;
    private Long requirementId;
    /** SessionTaskType.name */
    private String taskTypeCode;
    private String taskTypeName;
    /** GEN / REVIEW */
    private String taskTypeKind;
    private Long outputDirNodeId;
    private String outputDirLabel;
    /** 输出参考模板（Wiki「模板」下文件） */
    private Long outputTemplateWikiNodeId;
    private String outputTemplatePath;
    /** 规范（Wiki「规范」下目录） */
    private Long standardsWikiNodeId;
    private String standardsPath;
    private List<SessionContextVO> contexts = new ArrayList<>();
    private List<SessionInputVO> inputs = new ArrayList<>();
    private List<SessionCodeRepoVO> codeRepos = new ArrayList<>();
    private Long positionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    public static class SessionContextVO {
        private Long id;
        private Long wikiNodeId;
        private String nodeType;
        private String displayPath;
    }

    @Data
    public static class SessionInputVO {
        private Long id;
        private Long wikiNodeId;
        private String displayPath;
    }

    @Data
    public static class SessionCodeRepoVO {
        private Long id;
        private String localPath;
        private Integer sortNo;
    }
}
