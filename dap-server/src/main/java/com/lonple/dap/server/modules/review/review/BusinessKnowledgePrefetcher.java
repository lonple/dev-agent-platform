package com.lonple.dap.server.modules.review.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lonple.dap.server.modules.review.llm.client.LlmClient;
import com.lonple.dap.server.modules.review.llm.model.ChatMessage;
import com.lonple.dap.server.modules.review.llm.model.ChatRequest;
import com.lonple.dap.server.modules.review.llm.model.ChatResponse;
import com.lonple.dap.server.modules.review.knowledge.MemorySearchClient;
import com.lonple.dap.server.modules.review.log.ReviewLogService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据提交说明提炼业务检索问句，并对知识库各查一次；未命中不重试。
 */
@Service
public class BusinessKnowledgePrefetcher {
    private final LlmClient llmClient;
    private final MemorySearchClient memorySearchClient;
    private final ObjectMapper mapper;
    private final String extractPrompt;

    public BusinessKnowledgePrefetcher(LlmClient llmClient,
                                       MemorySearchClient memorySearchClient,
                                       ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.memorySearchClient = memorySearchClient;
        this.mapper = mapper;
        this.extractPrompt = loadClasspath("prompts/extract_business_queries.md");
    }

    public PrefetchResult prefetch(String taskId,
                                   ReviewLogService logService,
                                   String productName,
                                   List<String> commitMessages) {
        if (commitMessages == null || commitMessages.isEmpty()) {
            logService.warn(taskId, "KNOWLEDGE", "无提交说明，跳过业务知识检索");
            return PrefetchResult.skipped("无提交说明，未检索业务知识。业务问题请依据代码推断并标明。");
        }

        String joined = joinMessages(commitMessages);
        logService.info(taskId, "KNOWLEDGE",
                "根据提交说明提取业务主题（%d 条 message）".formatted(commitMessages.size()),
                Map.of("commits", commitMessages.size(), "chars", joined.length()));

        List<String> queries = extractQueries(taskId, logService, joined);
        if (queries.isEmpty()) {
            logService.info(taskId, "KNOWLEDGE", "提交说明未识别出业务检索问句，跳过知识库查询");
            return PrefetchResult.skipped("提交说明未识别出业务主题，未检索知识库。业务问题请依据代码推断并标明。");
        }

        logService.info(taskId, "KNOWLEDGE",
                "业务检索问句 " + queries.size() + " 条：" + String.join("；", queries));

        StringBuilder block = new StringBuilder();
        block.append("以下业务知识由「提交说明 → 主题提取 → 知识库检索」得到；每条问句只查一次，未命中不重试。\n");
        int totalHits = 0;
        int qi = 0;
        for (String q : queries) {
            qi++;
            logService.info(taskId, "KNOWLEDGE",
                    "检索业务知识（%d/%d）：%s".formatted(qi, queries.size(), abbrev(q, 80)),
                    Map.of("query", q, "product", String.valueOf(productName)));
            MemorySearchClient.KnowledgeSearchResult kr = memorySearchClient.search(q, productName, 5);
            logService.info(taskId, "KNOWLEDGE",
                    "知识检索完成：命中 %d 条".formatted(kr.hitCount()),
                    Map.of("hits", kr.hitCount(), "query", abbrev(q, 120)));
            block.append("\n### 问句 ").append(qi).append("：").append(q).append('\n');
            block.append(kr.summary()).append('\n');
            totalHits += Math.max(0, kr.hitCount());
            // 未命中：不对该问句改写重试；继续下一条问句（若有）
        }

        if (totalHits <= 0) {
            block.append("\n全部问句均未命中业务知识，**不会再次检索**。请基于 diff/上下文做谨慎业务评审，evidence 标明「业务知识未命中，依据代码推断」。\n");
            logService.warn(taskId, "KNOWLEDGE", "业务知识全部未命中，不再查询");
        } else {
            block.append("\n业务知识已提供完毕，评审阶段禁止再请求知识检索。\n");
            logService.info(taskId, "KNOWLEDGE", "业务知识预取完成，合计命中 %d 条".formatted(totalHits));
        }
        return new PrefetchResult(queries, totalHits, block.toString());
    }

    private List<String> extractQueries(String taskId, ReviewLogService logService, String commitText) {
        try {
            ChatResponse resp = llmClient.chat(ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(extractPrompt),
                            ChatMessage.user("产品上下文可忽略。提交说明如下：\n\n" + commitText)))
                    .responseFormat("json_object")
                    .temperature(0.1)
                    .caller("review-extract-biz")
                    .build());
            String content = resp.getContent() == null ? "" : resp.getContent().trim();
            JsonNode root = parseJson(content);
            if (root == null || !root.has("queries") || !root.get("queries").isArray()) {
                logService.warn(taskId, "KNOWLEDGE", "业务主题提取结果无法解析，跳过检索");
                return List.of();
            }
            Set<String> uniq = new LinkedHashSet<>();
            for (JsonNode n : root.get("queries")) {
                String q = n.asText("").trim();
                if (StringUtils.hasText(q)) {
                    uniq.add(q);
                }
                if (uniq.size() >= 3) {
                    break;
                }
            }
            return new ArrayList<>(uniq);
        } catch (Exception e) {
            logService.warn(taskId, "KNOWLEDGE", "业务主题提取失败: " + e.getMessage());
            return List.of();
        }
    }

    private static String joinMessages(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String m : messages) {
            i++;
            sb.append(i).append(". ").append(m.trim()).append("\n\n");
            if (sb.length() > 8000) {
                sb.append("...[提交说明已截断]...\n");
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

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String loadClasspath(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("缺少 Prompt: " + path, e);
        }
    }

    public record PrefetchResult(List<String> queries, int hitCount, String promptBlock) {
        static PrefetchResult skipped(String note) {
            return new PrefetchResult(List.of(), 0, note);
        }

        public boolean hasHits() {
            return hitCount > 0;
        }
    }
}
