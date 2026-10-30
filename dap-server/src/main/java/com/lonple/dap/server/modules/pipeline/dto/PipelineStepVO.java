package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PipelineStepVO {
    private Long stepInstanceId;
    private String stepCode;
    private String stepName;
    private String status;
    private Integer round;
    private String executor;
    private Long handlerUserId;
    private Long sessionId;
    private Long outputArtifactId;
    private Long reviewId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private List<PipelineAssigneeVO> assignees;
    /** 节点详情：输入交付件 Wiki 路径 */
    private List<String> inputPaths = new java.util.ArrayList<>();
    /** 产品上下文 Wiki 路径 */
    private List<String> contextPaths = new java.util.ArrayList<>();
    private String outputTemplatePath;
    private String outputDirPath;
    private List<String> codeRepoPaths = new java.util.ArrayList<>();
    private List<PipelineReviewItemVO> reviewItems = new java.util.ArrayList<>();
    /** 确认步：关联评审书 Wiki 节点（可多路合并预览） */
    private Long reviewWikiNodeId;
    private String reviewWikiPath;
    private List<PipelineReviewDocVO> reviewDocs = new java.util.ArrayList<>();
}
