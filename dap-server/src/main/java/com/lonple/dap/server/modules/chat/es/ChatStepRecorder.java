package com.lonple.dap.server.modules.chat.es;

import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将 SSE step 落 ES；按 runId 维护 seq。
 */
@Component
@RequiredArgsConstructor
public class ChatStepRecorder {

    private final ChatStepEsService chatStepEsService;
    private final ChatStepIndexProperties properties;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, AtomicInteger> seqByRun = new ConcurrentHashMap<>();

    public ChatStepWriteContext buildContext(WbChatSession session) {
        LoginUser user = UserContext.require();
        return ChatStepWriteContext.builder()
                .tenantId(session.getTenantId())
                .userId(user.getUserId())
                .username(user.getUsername())
                .workspaceId(session.getWorkspaceId())
                .sessionId(session.getId())
                .sessionTitle(session.getTitle())
                .requirementId(session.getRequirementId())
                .taskTypeCode(session.getTaskTypeCode())
                .positionId(session.getPositionId())
                .build();
    }

    public void recordStep(ChatStepWriteContext ctx, String runId, Long messageId, String stepJson) {
        if (ctx == null || !StringUtils.hasText(runId) || !StringUtils.hasText(stepJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(stepJson);
            String step = text(node, "step", "step");
            String message = text(node, "message", "");
            Integer llmRound = node.path("llmRound").isMissingNode() || node.path("llmRound").isNull()
                    ? null
                    : node.path("llmRound").asInt();
            Map<String, Object> detail = null;
            if (node.has("detail") && !node.get("detail").isNull()) {
                detail = objectMapper.convertValue(node.get("detail"), Map.class);
                detail = truncateDetail(detail);
            }
            int seq = seqByRun.computeIfAbsent(runId, k -> new AtomicInteger(0)).incrementAndGet();
            Instant now = Instant.now();
            String eventId = runId + ":" + seq;
            ChatStepDocument doc = ChatStepDocument.builder()
                    .eventId(eventId)
                    .category("CHAT_STEP")
                    .tenantId(ctx.getTenantId())
                    .userId(ctx.getUserId())
                    .username(ctx.getUsername())
                    .workspaceId(ctx.getWorkspaceId())
                    .sessionId(ctx.getSessionId())
                    .sessionTitle(ctx.getSessionTitle())
                    .requirementId(ctx.getRequirementId())
                    .taskTypeCode(ctx.getTaskTypeCode())
                    .positionId(ctx.getPositionId())
                    .messageId(messageId)
                    .runId(runId)
                    .seq(seq)
                    .llmRound(llmRound)
                    .step(step)
                    .message(message)
                    .detail(detail)
                    .status("ok")
                    .occurredAt(now.toString())
                    .build();
            chatStepEsService.index(doc);
        } catch (Exception ignored) {
            // 不阻断对话
        }
    }

    public void clearRun(String runId) {
        if (runId != null) {
            seqByRun.remove(runId);
        }
    }

    private String text(JsonNode node, String field, String def) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return def;
        }
        String v = n.asText();
        return StringUtils.hasText(v) ? v : def;
    }

    private Map<String, Object> truncateDetail(Map<String, Object> detail) {
        if (detail == null || properties.getStepDetailMaxChars() <= 0) {
            return detail;
        }
        try {
            String json = objectMapper.writeValueAsString(detail);
            if (json.length() <= properties.getStepDetailMaxChars()) {
                return detail;
            }
            return Map.of("_truncated", true, "_preview", json.substring(0, properties.getStepDetailMaxChars()));
        } catch (Exception e) {
            return detail;
        }
    }
}
