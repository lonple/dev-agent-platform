package com.lonple.dap.server.modules.review.review.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ReviewResult {
    private String runId;
    private String finishedAt;
    private String baseBranch;
    private String reviewBranch;
    private Stats stats = new Stats();
    private List<ReviewIssue> issues = new ArrayList<>();
    private Meta meta = new Meta();

    @Data
    public static class Stats {
        private int high;
        private int medium;
        private int low;
    }

    @Data
    public static class Meta {
        private int rounds;
        /** 分片全覆盖策略下恒为 false；保留反序列化兼容 */
        @Deprecated
        private boolean truncated;
        /** 是否存在未覆盖文件（正常应为 false） */
        private boolean coverageIncomplete;
        private String mode;
        private int shardCount;
        private int skippedOtherFiles;
        private List<String> shardIds = new ArrayList<>();
    }
}
