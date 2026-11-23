package com.lonple.dap.server.modules.review.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lonple.dap.server.modules.review.llm.client.LlmClient;
import com.lonple.dap.server.modules.review.llm.model.ChatMessage;
import com.lonple.dap.server.modules.review.llm.model.ChatRequest;
import com.lonple.dap.server.modules.review.llm.model.ChatResponse;
import com.lonple.dap.server.modules.review.log.ReviewLogService;
import com.lonple.dap.server.modules.review.partition.ReviewShard;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import com.lonple.dap.server.modules.review.store.ReviewProperties;
import com.lonple.dap.server.modules.review.task.TaskConfig;
import com.lonple.dap.server.modules.review.tool.ReadSourceTool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于现有 JSON action 协议的默认分片 Worker（不依赖 LangChain4j）。
 */
@Service
public class DefaultShardReviewWorker implements ShardReviewWorker {
    private final ReviewLogService logService;
    private final ReviewPromptService promptService;
    private final ReviewSchemaValidator validator;
    private final ReadSourceTool readSourceTool;
    private final BusinessKnowledgePrefetcher knowledgePrefetcher;
    private final LlmClient llmClient;
    private final ReviewProperties properties;
    private final ObjectMapper mapper;

    public DefaultShardReviewWorker(ReviewLogService logService,
                                    ReviewPromptService promptService,
                                    ReviewSchemaValidator validator,
                                    ReadSourceTool readSourceTool,
                                    BusinessKnowledgePrefetcher knowledgePrefetcher,
                                    LlmClient llmClient,
                                    ReviewProperties properties,
                                    ObjectMapper mapper) {
        this.logService = logService;
        this.promptService = promptService;
        this.validator = validator;
        this.readSourceTool = readSourceTool;
        this.knowledgePrefetcher = knowledgePrefetcher;
        this.llmClient = llmClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public ShardOutcome review(String taskId,
                               TaskConfig config,
                               Path workTree,
                               ReviewShard shard,
                               List<String> commitMessages) {
        String tag = "[" + shard.shardId() + "] ";
        long shardStart = System.currentTimeMillis();
        try {
            String rules = promptService.loadRules(shard.kind());
            logService.info(taskId, "RULES",
                    tag + "已加载规则 %s（%d 字符）".formatted(shard.kind().name(), rules.length()),
                    Map.of(
                            "shardId", shard.shardId(),
                            "kind", shard.kind().name(),
                            "path", promptService.rulesPath(shard.kind()).toString(),
                            "chars", rules.length()));

            BusinessKnowledgePrefetcher.PrefetchResult knowledge =
                    knowledgePrefetcher.prefetch(taskId, logService, config.getProductName(), commitMessages);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(promptService.systemPrompt()));
            String initialUser = buildInitialUser(config, shard, rules, commitMessages, knowledge.promptBlock());
            Path skipNotes = workTree.resolve(".dap-review-skip.md");
            if (Files.isRegularFile(skipNotes)) {
                String notes = Files.readString(skipNotes);
                if (StringUtils.hasText(notes)) {
                    initialUser = initialUser
                            + "\n\n【上一轮人工确认不修改，本轮不要再报同类问题】\n"
                            + notes;
                }
            }
            messages.add(ChatMessage.user(initialUser));
            logService.info(taskId, "REVIEW",
                    tag + "开场上下文就绪：diff=%d 字符 / 规则=%d / 知识命中=%d / 提交说明=%d / 首包约 %d 字符".formatted(
                            shard.charCount(),
                            rules.length(),
                            knowledge.hitCount(),
                            commitMessages == null ? 0 : commitMessages.size(),
                            initialUser.length()),
                    Map.of(
                            "shardId", shard.shardId(),
                            "diffChars", shard.charCount(),
                            "files", shard.fileCount(),
                            "ruleChars", rules.length(),
                            "knowledgeHits", knowledge.hitCount(),
                            "commits", commitMessages == null ? 0 : commitMessages.size(),
                            "userChars", initialUser.length()));

            int maxRounds = properties.getLlm().getMaxRounds();
            int rounds = 0;
            boolean needEvidenceRetry = false;
            int totalPromptTokens = 0;
            int totalCompletionTokens = 0;

            while (rounds < maxRounds) {
                rounds++;
                boolean lastRound = rounds >= maxRounds;
                int msgCount = messages.size();
                int approxInputChars = messages.stream()
                        .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                        .sum();
                logService.info(taskId, "LLM",
                        tag + "调用大模型 第 %d/%d 轮（messages=%d 输入约 %d 字符）".formatted(
                                rounds, maxRounds, msgCount, approxInputChars),
                        Map.of(
                                "shardId", shard.shardId(),
                                "round", rounds,
                                "maxRounds", maxRounds,
                                "messages", msgCount,
                                "inputChars", approxInputChars));
                long llmStart = System.currentTimeMillis();
                ChatResponse resp;
                try {
                    resp = llmClient.chat(ChatRequest.builder()
                            .messages(messages)
                            .responseFormat("json_object")
                            .temperature(0.2)
                            .caller("review-" + shard.kind().name().toLowerCase())
                            .build());
                } catch (Exception e) {
                    logService.error(taskId, "LLM",
                            tag + "模型调用失败（第 %d 轮，耗时 %d ms）: %s".formatted(
                                    rounds, System.currentTimeMillis() - llmStart, e.getMessage()),
                            Map.of("shardId", shard.shardId(), "round", rounds,
                                    "elapsedMs", System.currentTimeMillis() - llmStart));
                    return ShardOutcome.fail(shard.shardId(), e.getMessage());
                }
                long llmElapsed = System.currentTimeMillis() - llmStart;
                String content = resp.getContent() == null ? "" : resp.getContent().trim();
                int pt = resp.getPromptTokens() == null ? 0 : resp.getPromptTokens();
                int ct = resp.getCompletionTokens() == null ? 0 : resp.getCompletionTokens();
                totalPromptTokens += pt;
                totalCompletionTokens += ct;
                logService.info(taskId, "LLM",
                        tag + "模型返回 第 %d 轮：输出 %d 字符 / tokens %d+%d / 耗时 %d ms%s".formatted(
                                rounds, content.length(), pt, ct, llmElapsed,
                                resp.getModel() == null ? "" : " / model=" + resp.getModel()),
                        Map.of(
                                "shardId", shard.shardId(),
                                "round", rounds,
                                "chars", content.length(),
                                "promptTokens", pt,
                                "completionTokens", ct,
                                "elapsedMs", llmElapsed,
                                "model", resp.getModel() == null ? "" : resp.getModel()));

                JsonNode root = parseJson(content);
                if (root == null || !root.has("action")) {
                    logService.warn(taskId, "LLM",
                            tag + "无法解析 action，将原文回喂并重试（输出 %d 字符）".formatted(content.length()),
                            Map.of("shardId", shard.shardId(), "round", rounds, "chars", content.length()));
                    messages.add(ChatMessage.assistant(content.isBlank() ? "{\"action\":\"invalid\"}" : content));
                    messages.add(ChatMessage.user("上轮输出不是合法 JSON envelope，请严格按 schema 只输出 JSON。"
                            + (lastRound ? " 这是最后一轮，必须输出 final_review。" : "")));
                    continue;
                }
                String action = root.path("action").asText("");
                logService.info(taskId, "LLM",
                        tag + "action=%s（第 %d 轮）".formatted(action, rounds),
                        Map.of("shardId", shard.shardId(), "action", action, "round", rounds));

                if ("request_context".equals(action)) {
                    if (lastRound) {
                        messages.add(ChatMessage.assistant(content));
                        messages.add(ChatMessage.user("已达最大轮次，禁止再 request_context。请立即 final_review。"));
                        continue;
                    }
                    List<ContextReader.Target> targets = new ArrayList<>();
                    JsonNode arr = root.path("request_context").path("targets");
                    if (arr.isArray()) {
                        for (JsonNode t : arr) {
                            targets.add(ContextReader.Target.of(
                                    t.path("type").asText("CLASS"),
                                    t.path("name").asText(""),
                                    t.path("pathHint").asText(null)));
                        }
                    }
                    String targetNames = targets.stream()
                            .map(t -> t.name() == null ? "?" : t.name())
                            .limit(5)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    logService.info(taskId, "CONTEXT",
                            tag + "读取上下文：%d 个目标%s".formatted(
                                    targets.size(),
                                    targetNames.isBlank() ? "" : "（" + targetNames + (targets.size() > 5 ? " ..." : "") + "）"),
                            Map.of("targets", targets.size(), "shardId", shard.shardId()));
                    String ctx = readSourceTool.read(workTree, targets);
                    logService.info(taskId, "CONTEXT",
                            tag + "上下文回填 %d 字符".formatted(ctx.length()),
                            Map.of("shardId", shard.shardId(), "chars", ctx.length(), "targets", targets.size()));
                    messages.add(ChatMessage.assistant(content));
                    messages.add(ChatMessage.user("以下是请求的上下文代码：\n" + ctx));
                    continue;
                }
                if ("request_knowledge".equals(action)) {
                    logService.warn(taskId, "KNOWLEDGE", tag + "忽略 request_knowledge");
                    messages.add(ChatMessage.assistant(content));
                    messages.add(ChatMessage.user(
                            "禁止 request_knowledge。开场已根据本片提交说明完成业务知识检索。请 final_review 或 request_context。"));
                    continue;
                }
                if ("final_review".equals(action)) {
                    List<ReviewIssue> issues = validator.parseAndFilterIssues(root.path("final_review"));
                    int rawCount = root.path("final_review").path("issues").isArray()
                            ? root.path("final_review").path("issues").size() : 0;
                    if (rawCount > 0 && issues.isEmpty() && !needEvidenceRetry && !lastRound) {
                        needEvidenceRetry = true;
                        logService.warn(taskId, "REVIEW",
                                tag + "问题均缺少 evidence（raw=%d），要求模型补全依据".formatted(rawCount),
                                Map.of("shardId", shard.shardId(), "rawIssues", rawCount));
                        messages.add(ChatMessage.assistant(content));
                        messages.add(ChatMessage.user("你输出的 issues 均因缺少 evidence 被丢弃。请重新 final_review：每条 evidence 非空。"));
                        continue;
                    }
                    for (ReviewIssue issue : issues) {
                        issue.setShardId(shard.shardId());
                        issue.setShardKind(shard.kind().name());
                        issue.setModuleKey(shard.moduleKey());
                        if (StringUtils.hasText(issue.getId()) && !issue.getId().startsWith(shard.shardId())) {
                            issue.setId(shard.shardId() + "/" + issue.getId());
                        }
                    }
                    int high = countSev(issues, "HIGH");
                    int medium = countSev(issues, "MEDIUM");
                    int low = countSev(issues, "LOW");
                    long elapsed = System.currentTimeMillis() - shardStart;
                    Map<String, Object> doneDetail = new LinkedHashMap<>();
                    doneDetail.put("shardId", shard.shardId());
                    doneDetail.put("kind", shard.kind().name());
                    doneDetail.put("issues", issues.size());
                    doneDetail.put("high", high);
                    doneDetail.put("medium", medium);
                    doneDetail.put("low", low);
                    doneDetail.put("rawIssues", rawCount);
                    doneDetail.put("rounds", rounds);
                    doneDetail.put("promptTokens", totalPromptTokens);
                    doneDetail.put("completionTokens", totalCompletionTokens);
                    doneDetail.put("knowledgeHits", knowledge.hitCount());
                    doneDetail.put("elapsedMs", elapsed);
                    logService.info(taskId, "REVIEW",
                            tag + "分片完成：issues=%d（H=%d M=%d L=%d raw=%d）rounds=%d tokens=%d+%d 知识命中=%d 耗时 %d ms".formatted(
                                    issues.size(), high, medium, low, rawCount, rounds,
                                    totalPromptTokens, totalCompletionTokens, knowledge.hitCount(), elapsed),
                            doneDetail);
                    return ShardOutcome.ok(shard.shardId(), issues, rounds);
                }
                logService.warn(taskId, "LLM", tag + "未知 action: " + action,
                        Map.of("shardId", shard.shardId(), "action", action, "round", rounds));
                messages.add(ChatMessage.assistant(content));
                messages.add(ChatMessage.user("未知 action，请只使用 request_context / final_review。"
                        + (lastRound ? " 这是最后一轮，必须 final_review。" : "")));
            }
            logService.error(taskId, "REVIEW",
                    tag + "超过最大轮次仍未得到 final_review（max=%d tokens=%d+%d 耗时 %d ms）".formatted(
                            maxRounds, totalPromptTokens, totalCompletionTokens,
                            System.currentTimeMillis() - shardStart),
                    Map.of(
                            "shardId", shard.shardId(),
                            "maxRounds", maxRounds,
                            "promptTokens", totalPromptTokens,
                            "completionTokens", totalCompletionTokens,
                            "elapsedMs", System.currentTimeMillis() - shardStart));
            return ShardOutcome.fail(shard.shardId(), "超过最大轮次仍未得到 final_review");
        } catch (Exception e) {
            logService.error(taskId, "REVIEW",
                    tag + "分片失败（耗时 %d ms）: %s".formatted(
                            System.currentTimeMillis() - shardStart, e.getMessage()),
                    Map.of("shardId", shard.shardId(), "elapsedMs", System.currentTimeMillis() - shardStart));
            return ShardOutcome.fail(shard.shardId(), e.getMessage());
        }
    }

    private static int countSev(List<ReviewIssue> issues, String severity) {
        if (issues == null || issues.isEmpty()) {
            return 0;
        }
        return (int) issues.stream().filter(i -> severity.equalsIgnoreCase(i.getSeverity())).count();
    }

    private String buildInitialUser(TaskConfig config,
                                    ReviewShard shard,
                                    String rules,
                                    List<String> commitMessages,
                                    String knowledgeBlock) {
        String commits = commitMessages == null || commitMessages.isEmpty()
                ? "（无本片相关提交说明）"
                : String.join("\n---\n", commitMessages);
        return """
                产品名称: %s
                分片: %s | 类型: %s | 模块: %s
                基准分支: %s
                评审分支: %s
                本片文件数: %d

                ## 本轮任务（必须覆盖）
                1. 按下方《评审规范》做规范评审（类型=%s）。
                2. 审查业务流程是否正确；业务依据以开场「业务知识」为准。
                3. 可 request_context；禁止 request_knowledge；尽快 final_review。

                ## 本片相关提交说明
                %s

                ## 业务知识（预取，勿再检索）
                %s

                ## 评审规范（%s）
                %s

                ## 调用链相关文件（未改动，需要时用 request_context，服务端经 read_source 读取）
                %s

                ## Git Diff（本片）
                %s
                """.formatted(
                config.getProductName(),
                shard.shardId(),
                shard.kind().name(),
                shard.moduleKey(),
                config.getBaseBranch(),
                config.getReviewBranch(),
                shard.fileCount(),
                shard.kind().name(),
                commits,
                knowledgeBlock,
                shard.kind().name(),
                rules,
                shard.contextPaths() == null || shard.contextPaths().isEmpty()
                        ? "（无）"
                        : String.join("\n", shard.contextPaths()),
                shard.diffText());
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
