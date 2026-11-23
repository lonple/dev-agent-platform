package com.lonple.dap.server.modules.review.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lonple.dap.server.modules.review.llm.client.LlmClient;
import com.lonple.dap.server.modules.review.llm.model.ChatMessage;
import com.lonple.dap.server.modules.review.llm.model.ChatRequest;
import com.lonple.dap.server.modules.review.llm.model.ChatResponse;
import com.lonple.dap.server.modules.review.log.ReviewLogService;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对已定位的问题做一轮误报过滤。模型失败时保留原问题。
 */
@Component
public class IssueReflector {
    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public IssueReflector(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    public List<ReviewIssue> filter(String taskId, ReviewLogService logService, List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<ReviewIssue> located = new ArrayList<>();
        List<ReviewIssue> passthrough = new ArrayList<>();
        for (ReviewIssue issue : issues) {
            if (Boolean.TRUE.equals(issue.getLocated()) && StringUtils.hasText(issue.getId())) {
                located.add(issue);
            } else {
                passthrough.add(issue);
            }
        }
        if (located.isEmpty()) {
            return issues;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("判断下列代码评审问题是否成立。只输出 JSON：")
                .append("{\"decisions\":[{\"id\":\"问题id\",\"keep\":true}]}\n")
                .append("keep=false 表示误报（证据对不上代码、纯风格吹毛求疵、或描述与代码矛盾）。\n\n");
        for (ReviewIssue issue : located) {
            prompt.append("id=").append(issue.getId())
                    .append(" severity=").append(issue.getSeverity())
                    .append(" title=").append(issue.getTitle()).append('\n');
            if (issue.getLocation() != null) {
                prompt.append("path=").append(issue.getLocation().getPath())
                        .append(" line=").append(issue.getLocation().getStartLine()).append('\n');
            }
            prompt.append("evidence=").append(abbrev(issue.getEvidence(), 400)).append("\n\n");
        }
        try {
            ChatResponse resp = llmClient.chat(ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system("你是评审误报过滤器。只输出 JSON。"),
                            ChatMessage.user(prompt.toString())))
                    .responseFormat("json_object")
                    .temperature(0.0)
                    .caller("review-reflect")
                    .build());
            Set<String> drop = parseDrops(resp.getContent());
            List<ReviewIssue> kept = new ArrayList<>(passthrough);
            int dropped = 0;
            for (ReviewIssue issue : located) {
                if (drop.contains(issue.getId())) {
                    dropped++;
                } else {
                    kept.add(issue);
                }
            }
            logService.info(taskId, "REVIEW",
                    "误报过滤：送检 %d，丢弃 %d，保留 %d".formatted(located.size(), dropped, kept.size()),
                    Map.of("checked", located.size(), "dropped", dropped, "kept", kept.size()));
            return kept;
        } catch (Exception e) {
            logService.warn(taskId, "REVIEW", "误报过滤失败，保留原问题: " + e.getMessage());
            return issues;
        }
    }

    private Set<String> parseDrops(String content) {
        Set<String> drop = new LinkedHashSet<>();
        if (!StringUtils.hasText(content)) {
            return drop;
        }
        String json = content.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr = root.path("decisions");
            if (!arr.isArray()) {
                return drop;
            }
            for (JsonNode n : arr) {
                if (!n.path("keep").asBoolean(true) && n.has("id")) {
                    drop.add(n.path("id").asText());
                }
            }
        } catch (Exception ignored) {
            return drop;
        }
        return drop;
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
