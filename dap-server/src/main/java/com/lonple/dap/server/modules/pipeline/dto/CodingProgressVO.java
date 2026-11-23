package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 开发编码节点：四阶段进度（供前端子面板）。 */
@Data
public class CodingProgressVO {
    private Long unitId;
    private String unitName;
    private String unitStatus;
    private String currentStage;
    private Long currentSessionId;
    private String errorMessage;
    private List<StageSession> sessions = new ArrayList<>();
    private List<JobBrief> jobs = new ArrayList<>();
    private List<PlanTask> planTasks = new ArrayList<>();

    @Data
    public static class StageSession {
        private Long sessionId;
        private String title;
        private String createdAt;
    }

    @Data
    public static class JobBrief {
        private Long id;
        private String jobType;
        private String status;
        private String lastError;
        private String payloadJson;
        private String updatedAt;
    }

    @Data
    public static class PlanTask {
        private String id;
        private String title;
        private String module;
        private String status;
        private Long sessionId;
    }
}
