package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PipelineInstanceVO {
    private Long id;
    private Long requirementId;
    private Long issueId;
    private String issueNo;
    private Long productId;
    private String reqNo;
    private String requirementName;
    private Long definitionId;
    private String definitionCode;
    private String definitionName;
    private String status;
    private String waitingReason;
    private String optionsJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<NodeVO> nodes = new ArrayList<>();
    private List<WorktreeVO> worktrees = new ArrayList<>();
    /** 各轨合并状态（评审齐后出现） */
    private List<PipelineTrackMergeVO> trackMerges = new ArrayList<>();

    @Data
    public static class NodeVO {
        private Long id;
        private String nodeCode;
        private String nodeName;
        private String kind;
        private String status;
        private Boolean optionalFlag;
        private Boolean enabledFlag;
        private Integer sortOrder;
        private List<UnitVO> units = new ArrayList<>();
    }

    @Data
    public static class UnitVO {
        private Long id;
        private String unitCode;
        private String unitName;
        private String positionCode;
        private String taskTypeCode;
        private String status;
        private String roundTier;
        private Integer maxRound;
        private Integer confirmRound;
        private String repoKind;
        private Long sessionId;
        private Long outputWorkspaceNodeId;
        private Long outputWikiNodeId;
        private String errorMessage;
        /** 会话实际绑定的上下文 Wiki 展示路径（来自 wb_session_context.display_path） */
        private List<String> contextPaths = new ArrayList<>();
    }

    @Data
    public static class WorktreeVO {
        private Long id;
        private Long productCodeRepoId;
        private String repoKind;
        private String branchName;
        private String worktreePath;
        private String baseLocalPath;
        private String status;
    }
}
