package com.lonple.dap.server.modules.review.review;

import com.lonple.dap.server.modules.review.codegraph.CodeGraphService;
import com.lonple.dap.server.modules.review.git.GitService;
import com.lonple.dap.server.modules.review.log.ReviewLogService;
import com.lonple.dap.server.modules.review.codegraph.CodeGraphIndex;
import com.lonple.dap.server.modules.review.partition.ChangeBundler;
import com.lonple.dap.server.modules.review.partition.DiffFile;
import com.lonple.dap.server.modules.review.partition.DiffPartitioner;
import com.lonple.dap.server.modules.review.partition.GraphChangeClusterer;
import com.lonple.dap.server.modules.review.partition.ReviewShard;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import com.lonple.dap.server.modules.review.review.model.ReviewResult;
import com.lonple.dap.server.modules.review.store.JsonTaskStore;
import com.lonple.dap.server.modules.review.task.TaskConfig;
import com.lonple.dap.server.modules.review.task.TaskService;
import com.lonple.dap.server.modules.review.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class ReviewOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final TaskService taskService;
    private final JsonTaskStore store;
    private final GitService gitService;
    private final ReviewLogService logService;
    private final GraphChangeClusterer graphChangeClusterer;
    private final ShardReviewWorker shardWorker;
    private final ResultMerger resultMerger;
    private final CrossReviewService crossReviewService;
    private final ChangeBundler changeBundler;
    private final CodeGraphService codeGraphService;
    private final IssueLocator issueLocator;
    private final IssueReflector issueReflector;
    private final Executor reviewExecutor;
    private final Executor shardExecutor;

    public ReviewOrchestrator(TaskService taskService,
                              JsonTaskStore store,
                              GitService gitService,
                              ReviewLogService logService,
                              GraphChangeClusterer graphChangeClusterer,
                              ShardReviewWorker shardWorker,
                              ResultMerger resultMerger,
                              CrossReviewService crossReviewService,
                              ChangeBundler changeBundler,
                              CodeGraphService codeGraphService,
                              IssueLocator issueLocator,
                              IssueReflector issueReflector,
                              @Qualifier("reviewExecutor") Executor reviewExecutor,
                              @Qualifier("shardExecutor") Executor shardExecutor) {
        this.taskService = taskService;
        this.store = store;
        this.gitService = gitService;
        this.logService = logService;
        this.graphChangeClusterer = graphChangeClusterer;
        this.shardWorker = shardWorker;
        this.resultMerger = resultMerger;
        this.crossReviewService = crossReviewService;
        this.changeBundler = changeBundler;
        this.codeGraphService = codeGraphService;
        this.issueLocator = issueLocator;
        this.issueReflector = issueReflector;
        this.reviewExecutor = reviewExecutor;
        this.shardExecutor = shardExecutor;
    }

    public void runAsync(String taskId) {
        reviewExecutor.execute(() -> {
            try {
                run(taskId);
            } catch (Exception e) {
                log.error("review failed taskId={}", taskId, e);
                logService.error(taskId, "REVIEW", "评审失败: " + e.getMessage());
                taskService.markStatus(taskId, TaskStatus.FAILED);
            }
        });
    }

    public void run(String taskId) {
        try {
            execute(taskId);
        } finally {
            codeGraphService.release(taskId);
        }
    }

    /**
     * 流水线入口：对 feat worktree 相对默认分支做一次三方 diff 评审，返回问题列表（可为空）。
     */
    public ReviewResult reviewWorktree(String taskId, Path workTree, String baseRef, String headRef) {
        TaskConfig config = new TaskConfig();
        config.setId(taskId);
        config.setName("pipeline-" + taskId);
        config.setProductName("pipeline");
        config.setStatus(TaskStatus.RUNNING);
        config.setRepoType(com.lonple.dap.server.modules.review.git.RepoType.LOCAL);
        config.setLocalPath(workTree.toString());
        config.setWorkTreePath(workTree.toAbsolutePath().normalize().toString());
        config.setBaseBranch(baseRef);
        config.setReviewBranch(headRef);
        store.saveConfig(config);
        run(taskId);
        ReviewResult result = store.loadResult(taskId);
        if (result == null) {
            throw new com.lonple.dap.server.common.BizException("代码评审未产出结果");
        }
        if (result.getIssues() == null) {
            result.setIssues(List.of());
        }
        return result;
    }

    private void execute(String taskId) {
        TaskConfig config = store.loadConfig(taskId);
        long t0 = System.currentTimeMillis();
        logService.info(taskId, "REVIEW", "──────── 开始代码评审 ────────", Map.of(
                "product", String.valueOf(config.getProductName()),
                "base", String.valueOf(config.getBaseBranch()),
                "review", String.valueOf(config.getReviewBranch())));
        Path workTree = Path.of(config.getWorkTreePath()).toAbsolutePath().normalize();

        logService.info(taskId, "DIFF",
                "开始计算 diff：%s → %s（按文件拆分，不整包截断）".formatted(
                        config.getBaseBranch(), config.getReviewBranch()),
                Map.of("base", config.getBaseBranch(), "review", config.getReviewBranch()));
        long tDiff = System.currentTimeMillis();
        List<DiffFile> files = gitService.diffFiles(workTree, config.getBaseBranch(), config.getReviewBranch());
        int totalChars = files.stream().mapToInt(DiffFile::charCount).sum();
        logService.info(taskId, "DIFF",
                "diff 完成：%d 文件 / %d 字符 / 耗时 %d ms".formatted(
                        files.size(), totalChars, System.currentTimeMillis() - tDiff),
                Map.of("files", files.size(), "chars", totalChars,
                        "elapsedMs", System.currentTimeMillis() - tDiff));

        if (files.isEmpty()) {
            logService.warn(taskId, "DIFF", "diff 为空（0 个文件变更），结束评审");
            ReviewResult empty = baseResult(config, DiffPartitioner.Mode.SINGLE.name(), 0, 0, List.of());
            store.saveResult(taskId, empty);
            taskService.markStatus(taskId, TaskStatus.DONE);
            return;
        }

        CodeGraphIndex graph = codeGraphService.indexForTask(taskId, workTree);
        DiffPartitioner.PartitionPlan plan = graphChangeClusterer.partition(files, graph);
        Map<String, Integer> kindCounts = kindFileCounts(plan.shards());
        int shardChars = plan.shards().stream().mapToInt(ReviewShard::charCount).sum();
        int chainShards = graphChangeClusterer.chainShardCount(plan.shards());
        logService.info(taskId, "PARTITION",
                "分片完成：mode=%s，%d 片（调用链簇 %d）/ %d 字符，跳过 OTHER %d；类型分布 %s".formatted(
                        plan.mode(),
                        plan.shards().size(),
                        chainShards,
                        shardChars,
                        plan.skippedOther().size(),
                        formatKindCounts(kindCounts)),
                Map.of(
                        "mode", plan.mode().name(),
                        "shards", plan.shards().size(),
                        "chainShards", chainShards,
                        "shardChars", shardChars,
                        "skippedOther", plan.skippedOther().size(),
                        "kindCounts", kindCounts));

        if (!plan.skippedOther().isEmpty()) {
            String sample = plan.skippedOther().stream()
                    .limit(8)
                    .map(DiffFile::path)
                    .collect(Collectors.joining(", "));
            logService.info(taskId, "PARTITION",
                    "OTHER 跳过 %d 个文件：%s%s".formatted(
                            plan.skippedOther().size(),
                            sample,
                            plan.skippedOther().size() > 8 ? " ..." : ""),
                    Map.of("count", plan.skippedOther().size()));
        }

        if (plan.shards().isEmpty()) {
            logService.warn(taskId, "PARTITION", "无可评审分片（可能全部为 OTHER），结束");
            ReviewResult empty = baseResult(config, plan.mode().name(), 0, plan.skippedOther().size(), List.of());
            empty.getMeta().setCoverageIncomplete(!plan.skippedOther().isEmpty());
            store.saveResult(taskId, empty);
            taskService.markStatus(taskId, TaskStatus.DONE);
            return;
        }

        List<ReviewShard> shards = changeBundler.repartition(plan.shards());
        int bundled = (int) shards.stream().filter(s -> s.shardId().startsWith("bundle-")).count();
        logService.info(taskId, "PARTITION",
                "契约打包：%d 包，剩余分片 %d".formatted(bundled, shards.size() - bundled),
                Map.of("bundles", bundled, "shards", shards.size()));
        shards = codeGraphService.attach(shards, graph);
        int withChain = (int) shards.stream().filter(s -> !s.contextPaths().isEmpty()).count();
        logService.info(taskId, "CONTEXT",
                "调用链上下文：%d 个分片附带未改动文件".formatted(withChain),
                Map.of("shardsWithContext", withChain));

        for (ReviewShard shard : shards) {
            logService.info(taskId, "PARTITION",
                    "分片就绪 [%s] kind=%s module=%s files=%d chars=%d".formatted(
                            shard.shardId(),
                            shard.kind().name(),
                            shard.moduleKey(),
                            shard.fileCount(),
                            shard.charCount()),
                    Map.of(
                            "shardId", shard.shardId(),
                            "kind", shard.kind().name(),
                            "module", shard.moduleKey(),
                            "files", shard.fileCount(),
                            "chars", shard.charCount()));
        }

        long tShards = System.currentTimeMillis();
        List<ShardReviewWorker.ShardOutcome> outcomes = runShards(taskId, config, workTree, shards);
        long shardsElapsed = System.currentTimeMillis() - tShards;
        List<List<ReviewIssue>> batches = new ArrayList<>();
        int totalRounds = 0;
        int okShards = 0;
        int failShards = 0;
        int shardIssueCount = 0;
        boolean anyFail = false;
        for (ShardReviewWorker.ShardOutcome o : outcomes) {
            totalRounds += o.rounds();
            if (!o.success()) {
                anyFail = true;
                failShards++;
                logService.error(taskId, "REVIEW",
                        "[%s] 分片失败：%s".formatted(o.shardId(), o.error()),
                        Map.of("shardId", o.shardId(), "rounds", o.rounds()));
            } else {
                okShards++;
                batches.add(o.issues());
                shardIssueCount += o.issues() == null ? 0 : o.issues().size();
            }
        }
        logService.info(taskId, "REVIEW",
                "分片评审结束：成功 %d / 失败 %d / 共 %d 片，片内 issues=%d，合计轮次=%d，耗时 %d ms".formatted(
                        okShards, failShards, outcomes.size(), shardIssueCount, totalRounds, shardsElapsed),
                Map.of(
                        "ok", okShards,
                        "fail", failShards,
                        "shards", outcomes.size(),
                        "issues", shardIssueCount,
                        "rounds", totalRounds,
                        "elapsedMs", shardsElapsed));

        if (anyFail && batches.isEmpty()) {
            logService.error(taskId, "REVIEW", "全部分片失败");
            taskService.markStatus(taskId, TaskStatus.FAILED);
            return;
        }

        int beforeMerge = batches.stream().mapToInt(List::size).sum();
        List<ReviewIssue> merged = resultMerger.merge(batches);
        logService.info(taskId, "MERGE",
                "结果合并：输入 %d → 去重后 %d（H/M/L=%s）".formatted(
                        beforeMerge, merged.size(), severityBrief(merged)),
                Map.of(
                        "before", beforeMerge,
                        "after", merged.size(),
                        "high", countSeverity(merged, "HIGH"),
                        "medium", countSeverity(merged, "MEDIUM"),
                        "low", countSeverity(merged, "LOW")));

        long tCross = System.currentTimeMillis();
        List<ReviewIssue> cross = crossReviewService.run(
                taskId, logService, config, changeBundler.unbundled(shards), merged);
        if (!cross.isEmpty()) {
            int beforeCrossMerge = merged.size() + cross.size();
            merged = resultMerger.merge(List.of(merged, cross));
            logService.info(taskId, "MERGE",
                    "并入 CrossReview：新增 %d → 合计 %d（耗时 %d ms）".formatted(
                            cross.size(), merged.size(), System.currentTimeMillis() - tCross),
                    Map.of(
                            "crossIssues", cross.size(),
                            "total", merged.size(),
                            "beforeMerge", beforeCrossMerge,
                            "elapsedMs", System.currentTimeMillis() - tCross));
        }

        ReviewResult result = baseResult(
                config,
                plan.mode().name(),
                shards.size(),
                plan.skippedOther().size(),
                shards.stream().map(ReviewShard::shardId).toList());
        result.getMeta().setRounds(totalRounds);
        result.getMeta().setCoverageIncomplete(!plan.skippedOther().isEmpty());
        int beforeLocate = merged.size();
        merged = issueLocator.locate(workTree, shards, merged);
        logService.info(taskId, "REVIEW",
                "行号对齐：%d → %d".formatted(beforeLocate, merged.size()),
                Map.of("before", beforeLocate, "after", merged.size()));
        merged = issueReflector.filter(taskId, logService, merged);
        result.setIssues(merged);
        result.getStats().setHigh(countSeverity(merged, "HIGH"));
        result.getStats().setMedium(countSeverity(merged, "MEDIUM"));
        result.getStats().setLow(countSeverity(merged, "LOW"));
        store.saveResult(taskId, result);

        long totalElapsed = System.currentTimeMillis() - t0;
        logService.info(taskId, "REVIEW",
                "评审完成：mode=%s shards=%d issues=%d（H=%d M=%d L=%d）rounds=%d partialFail=%s 总耗时 %d ms".formatted(
                        plan.mode().name(),
                        shards.size(),
                        merged.size(),
                        result.getStats().getHigh(),
                        result.getStats().getMedium(),
                        result.getStats().getLow(),
                        totalRounds,
                        anyFail,
                        totalElapsed),
                Map.of(
                        "mode", plan.mode().name(),
                        "shards", shards.size(),
                        "issues", merged.size(),
                        "high", result.getStats().getHigh(),
                        "medium", result.getStats().getMedium(),
                        "low", result.getStats().getLow(),
                        "rounds", totalRounds,
                        "partialFail", anyFail,
                        "crossIssues", cross.size(),
                        "elapsedMs", totalElapsed));
        taskService.markStatus(taskId, TaskStatus.DONE);
    }

    private List<ShardReviewWorker.ShardOutcome> runShards(String taskId,
                                                           TaskConfig config,
                                                           Path workTree,
                                                           List<ReviewShard> shards) {
        if (shards.size() == 1) {
            ReviewShard shard = shards.get(0);
            logService.info(taskId, "REVIEW",
                    "单分片串行评审：[%s] kind=%s files=%d chars=%d".formatted(
                            shard.shardId(), shard.kind().name(), shard.fileCount(), shard.charCount()),
                    Map.of(
                            "parallel", false,
                            "shards", 1,
                            "shardId", shard.shardId(),
                            "files", shard.fileCount(),
                            "chars", shard.charCount()));
            List<String> messages = loadShardMessages(workTree, config, shard);
            return List.of(shardWorker.review(taskId, config, workTree, shard, messages));
        }

        String ids = shards.stream().map(ReviewShard::shardId).collect(Collectors.joining(", "));
        int files = shards.stream().mapToInt(ReviewShard::fileCount).sum();
        int chars = shards.stream().mapToInt(ReviewShard::charCount).sum();
        logService.info(taskId, "REVIEW",
                "多分片并行评审：%d 片同时提交（files=%d chars=%d）：%s".formatted(
                        shards.size(), files, chars, ids),
                Map.of(
                        "parallel", true,
                        "shards", shards.size(),
                        "files", files,
                        "chars", chars,
                        "shardIds", ids));

        List<CompletableFuture<ShardReviewWorker.ShardOutcome>> futures = new ArrayList<>();
        for (ReviewShard shard : shards) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<String> messages = loadShardMessages(workTree, config, shard);
                return shardWorker.review(taskId, config, workTree, shard, messages);
            }, shardExecutor));
        }
        List<ShardReviewWorker.ShardOutcome> out = new ArrayList<>();
        for (CompletableFuture<ShardReviewWorker.ShardOutcome> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    private List<String> loadShardMessages(Path workTree, TaskConfig config, ReviewShard shard) {
        try {
            List<String> msgs = gitService.commitMessages(
                    workTree, config.getBaseBranch(), config.getReviewBranch(), shard.paths());
            int msgChars = msgs.stream().mapToInt(String::length).sum();
            logService.info(config.getId(), "DIFF",
                    "[%s] 片级提交说明 %d 条 / %d 字符".formatted(shard.shardId(), msgs.size(), msgChars),
                    Map.of("shardId", shard.shardId(), "commits", msgs.size(), "chars", msgChars));
            return msgs;
        } catch (Exception e) {
            logService.warn(config.getId(), "DIFF",
                    "[%s] 读取片级提交说明失败: %s".formatted(shard.shardId(), e.getMessage()));
            return List.of();
        }
    }

    private static Map<String, Integer> kindFileCounts(List<ReviewShard> shards) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ReviewShard s : shards) {
            map.merge(s.kind().name(), s.fileCount(), Integer::sum);
        }
        return map;
    }

    private static String formatKindCounts(Map<String, Integer> kindCounts) {
        if (kindCounts == null || kindCounts.isEmpty()) {
            return "（无）";
        }
        return kindCounts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "));
    }

    private static int countSeverity(List<ReviewIssue> issues, String severity) {
        if (issues == null || issues.isEmpty()) {
            return 0;
        }
        return (int) issues.stream().filter(i -> severity.equalsIgnoreCase(i.getSeverity())).count();
    }

    private static String severityBrief(List<ReviewIssue> issues) {
        return "%d/%d/%d".formatted(
                countSeverity(issues, "HIGH"),
                countSeverity(issues, "MEDIUM"),
                countSeverity(issues, "LOW"));
    }

    private ReviewResult baseResult(TaskConfig config,
                                    String mode,
                                    int shardCount,
                                    int skippedOther,
                                    List<String> shardIds) {
        ReviewResult result = new ReviewResult();
        result.setRunId("run-" + UUID.randomUUID().toString().substring(0, 8));
        result.setFinishedAt(Instant.now().toString());
        result.setBaseBranch(config.getBaseBranch());
        result.setReviewBranch(config.getReviewBranch());
        result.getMeta().setMode(mode);
        result.getMeta().setShardCount(shardCount);
        result.getMeta().setSkippedOtherFiles(skippedOther);
        result.getMeta().setTruncated(false);
        result.getMeta().setCoverageIncomplete(false);
        result.getMeta().setShardIds(new ArrayList<>(shardIds));
        return result;
    }
}
