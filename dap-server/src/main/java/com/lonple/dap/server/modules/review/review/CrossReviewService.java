package com.lonple.dap.server.modules.review.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lonple.dap.server.modules.review.llm.client.LlmClient;
import com.lonple.dap.server.modules.review.llm.model.ChatMessage;
import com.lonple.dap.server.modules.review.llm.model.ChatRequest;
import com.lonple.dap.server.modules.review.llm.model.ChatResponse;
import com.lonple.dap.server.modules.review.log.ReviewLogService;
import com.lonple.dap.server.modules.review.partition.DiffFile;
import com.lonple.dap.server.modules.review.partition.FileTypeClassifier;
import com.lonple.dap.server.modules.review.partition.ReviewShard;
import com.lonple.dap.server.modules.review.partition.ShardKind;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import com.lonple.dap.server.modules.review.task.TaskConfig;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 轻量跨片契约评审（一轮，无 request_context）。
 */
@Service
public class CrossReviewService {
    private final LlmClient llmClient;
    private final ReviewPromptService promptService;
    private final ReviewSchemaValidator validator;
    private final FileTypeClassifier classifier;
    private final ObjectMapper mapper;

    public CrossReviewService(LlmClient llmClient,
                              ReviewPromptService promptService,
                              ReviewSchemaValidator validator,
                              FileTypeClassifier classifier,
                              ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.validator = validator;
        this.classifier = classifier;
        this.mapper = mapper;
    }

    public boolean shouldRun(List<ReviewShard> shards) {
        Set<ShardKind> kinds = shardKinds(shards);
        boolean feBe = kinds.contains(ShardKind.FRONTEND) && kinds.contains(ShardKind.BACKEND);
        boolean beXml = kinds.contains(ShardKind.BACKEND) && kinds.contains(ShardKind.MYBATIS_XML);
        boolean beSql = kinds.contains(ShardKind.BACKEND) && kinds.contains(ShardKind.SQL);
        return feBe || beXml || beSql;
    }

    public List<ReviewIssue> run(String taskId,
                                 ReviewLogService logService,
                                 TaskConfig config,
                                 List<ReviewShard> shards,
                                 List<ReviewIssue> existingHighMedium) {
        Set<ShardKind> kinds = shardKinds(shards);
        String kindsLabel = kinds.stream().map(Enum::name).collect(Collectors.joining("+"));
        String triggers = triggerReasons(kinds);
        if (triggers.isBlank()) {
            logService.info(taskId, "CROSS",
                    "跳过跨片评审：当前类型组合 [%s] 不满足 FE+BE / BE+XML / BE+SQL".formatted(kindsLabel),
                    Map.of("kinds", kindsLabel, "shards", shards == null ? 0 : shards.size()));
            return List.of();
        }

        String contractDiff = extractContractDiff(shards);
        int contractFiles = countContractFiles(shards);
        String issueBrief = summarizeIssues(existingHighMedium);
        int hmInput = countHighMedium(existingHighMedium);
        long t0 = System.currentTimeMillis();

        logService.info(taskId, "CROSS",
                "开始跨片契约评审：触发=%s；类型=%s；片级 H/M 摘要=%d；契约文件=%d / diff=%d 字符".formatted(
                        triggers, kindsLabel, hmInput, contractFiles, contractDiff.length()),
                Map.of(
                        "triggers", triggers,
                        "kinds", kindsLabel,
                        "shards", shards.size(),
                        "inputHighMedium", hmInput,
                        "contractFiles", contractFiles,
                        "contractDiffChars", contractDiff.length()));

        String user = """
                产品: %s
                基准: %s → 评审: %s

                ## 各分片 HIGH/MEDIUM 摘要
                %s

                ## 疑似契约相关 diff
                %s
                """.formatted(
                config.getProductName(),
                config.getBaseBranch(),
                config.getReviewBranch(),
                issueBrief.isBlank() ? "（无）" : issueBrief,
                contractDiff.isBlank() ? "（未识别到契约文件）" : contractDiff);

        try {
            logService.info(taskId, "LLM",
                    "[cross] 调用大模型（跨片一轮，输入约 %d 字符）".formatted(user.length()),
                    Map.of("inputChars", user.length(), "caller", "review-cross"));
            long llmStart = System.currentTimeMillis();
            ChatResponse resp = llmClient.chat(ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(promptService.crossSystemPrompt()),
                            ChatMessage.user(user)))
                    .responseFormat("json_object")
                    .temperature(0.2)
                    .caller("review-cross")
                    .build());
            long llmElapsed = System.currentTimeMillis() - llmStart;
            String content = resp.getContent() == null ? "" : resp.getContent().trim();
            int pt = resp.getPromptTokens() == null ? 0 : resp.getPromptTokens();
            int ct = resp.getCompletionTokens() == null ? 0 : resp.getCompletionTokens();
            logService.info(taskId, "LLM",
                    "[cross] 模型返回：输出 %d 字符 / tokens %d+%d / 耗时 %d ms".formatted(
                            content.length(), pt, ct, llmElapsed),
                    Map.of(
                            "chars", content.length(),
                            "promptTokens", pt,
                            "completionTokens", ct,
                            "elapsedMs", llmElapsed,
                            "model", resp.getModel() == null ? "" : resp.getModel()));

            JsonNode root = parseJson(content);
            if (root == null) {
                logService.warn(taskId, "CROSS",
                        "跨片结果无法解析（输出 %d 字符，耗时 %d ms）".formatted(
                                content.length(), System.currentTimeMillis() - t0),
                        Map.of("chars", content.length(), "elapsedMs", System.currentTimeMillis() - t0));
                return List.of();
            }
            JsonNode finalNode = root.has("final_review") ? root.path("final_review") : root;
            List<ReviewIssue> issues = validator.parseAndFilterIssues(finalNode);
            int rawCount = finalNode.path("issues").isArray() ? finalNode.path("issues").size() : 0;
            for (ReviewIssue issue : issues) {
                issue.setShardId("cross");
                issue.setShardKind("CROSS");
                issue.setModuleKey("_cross");
                if (!StringUtils.hasText(issue.getEvidenceType())) {
                    issue.setEvidenceType("CROSS");
                }
                if (StringUtils.hasText(issue.getId()) && !issue.getId().startsWith("cross/")) {
                    issue.setId("cross/" + issue.getId());
                }
            }
            int high = countSev(issues, "HIGH");
            int medium = countSev(issues, "MEDIUM");
            int low = countSev(issues, "LOW");
            long elapsed = System.currentTimeMillis() - t0;
            logService.info(taskId, "CROSS",
                    "跨片评审完成：issues=%d（H=%d M=%d L=%d raw=%d）tokens=%d+%d 耗时 %d ms".formatted(
                            issues.size(), high, medium, low, rawCount, pt, ct, elapsed),
                    Map.of(
                            "issues", issues.size(),
                            "high", high,
                            "medium", medium,
                            "low", low,
                            "rawIssues", rawCount,
                            "promptTokens", pt,
                            "completionTokens", ct,
                            "contractDiffChars", contractDiff.length(),
                            "elapsedMs", elapsed));
            return issues;
        } catch (Exception e) {
            logService.warn(taskId, "CROSS",
                    "跨片评审失败（忽略，耗时 %d ms）: %s".formatted(
                            System.currentTimeMillis() - t0, e.getMessage()),
                    Map.of("elapsedMs", System.currentTimeMillis() - t0));
            return List.of();
        }
    }

    private static Set<ShardKind> shardKinds(List<ReviewShard> shards) {
        if (shards == null || shards.isEmpty()) {
            return EnumSet.noneOf(ShardKind.class);
        }
        return shards.stream().map(ReviewShard::kind).collect(Collectors.toCollection(() -> EnumSet.noneOf(ShardKind.class)));
    }

    private static String triggerReasons(Set<ShardKind> kinds) {
        Set<String> reasons = new LinkedHashSet<>();
        if (kinds.contains(ShardKind.FRONTEND) && kinds.contains(ShardKind.BACKEND)) {
            reasons.add("FE+BE");
        }
        if (kinds.contains(ShardKind.BACKEND) && kinds.contains(ShardKind.MYBATIS_XML)) {
            reasons.add("BE+XML");
        }
        if (kinds.contains(ShardKind.BACKEND) && kinds.contains(ShardKind.SQL)) {
            reasons.add("BE+SQL");
        }
        return String.join(",", reasons);
    }

    private String extractContractDiff(List<ReviewShard> shards) {
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        int max = 40_000;
        for (ReviewShard shard : shards) {
            for (DiffFile f : shard.files()) {
                if (!isContractPath(f.path(), classifier.classify(f.path()))) {
                    continue;
                }
                if (chars + f.charCount() > max) {
                    sb.append("\n... [contract diff truncated] ...\n");
                    return sb.toString();
                }
                sb.append(f.patch());
                if (!f.patch().endsWith("\n")) {
                    sb.append('\n');
                }
                chars += f.charCount();
            }
        }
        return sb.toString();
    }

    private int countContractFiles(List<ReviewShard> shards) {
        int n = 0;
        for (ReviewShard shard : shards) {
            for (DiffFile f : shard.files()) {
                if (isContractPath(f.path(), classifier.classify(f.path()))) {
                    n++;
                }
            }
        }
        return n;
    }

    static boolean isContractPath(String path, ShardKind kind) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (kind == ShardKind.MYBATIS_XML || kind == ShardKind.SQL) {
            return true;
        }
        if (kind == ShardKind.FRONTEND) {
            return lower.contains("/api/") || lower.contains("/apis/") || name.contains("api");
        }
        if (kind == ShardKind.BACKEND) {
            return name.endsWith("controller.java")
                    || name.endsWith("dto.java")
                    || name.endsWith("vo.java")
                    || name.endsWith("request.java")
                    || name.endsWith("response.java")
                    || name.endsWith("facade.java")
                    || name.endsWith("api.java")
                    || lower.contains("/api/")
                    || lower.contains("/dto/")
                    || lower.contains("/controller/");
        }
        return false;
    }

    private static int countHighMedium(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return 0;
        }
        return (int) issues.stream()
                .filter(i -> i.getSeverity() != null)
                .filter(i -> {
                    String sev = i.getSeverity().toUpperCase(Locale.ROOT);
                    return "HIGH".equals(sev) || "MEDIUM".equals(sev);
                })
                .count();
    }

    private static int countSev(List<ReviewIssue> issues, String severity) {
        if (issues == null || issues.isEmpty()) {
            return 0;
        }
        return (int) issues.stream().filter(i -> severity.equalsIgnoreCase(i.getSeverity())).count();
    }

    private static String summarizeIssues(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ReviewIssue i : issues) {
            if (i.getSeverity() == null) {
                continue;
            }
            String sev = i.getSeverity().toUpperCase(Locale.ROOT);
            if (!"HIGH".equals(sev) && !"MEDIUM".equals(sev)) {
                continue;
            }
            n++;
            sb.append(n).append(". [").append(sev).append("] ")
                    .append(i.getShardId() == null ? "" : i.getShardId() + " · ")
                    .append(i.getTitle() == null ? "" : i.getTitle());
            if (i.getLocation() != null && StringUtils.hasText(i.getLocation().getPath())) {
                sb.append(" @ ").append(i.getLocation().getPath());
            }
            sb.append('\n');
            if (n >= 40) {
                sb.append("...[更多省略]...\n");
                break;
            }
        }
        return sb.toString();
    }

    private JsonNode parseJson(String content) {
        String json = content;
        if (json.startsWith("```")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
