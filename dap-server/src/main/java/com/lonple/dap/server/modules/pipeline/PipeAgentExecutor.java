package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.integration.agent.AgentClient;
import com.lonple.dap.server.modules.agenttool.AgentFsToolService;
import com.lonple.dap.server.modules.audit.TokenUsageService;
import com.lonple.dap.server.modules.chat.entity.WbChatMessage;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.es.ChatStepRecorder;
import com.lonple.dap.server.modules.chat.es.ChatStepWriteContext;
import com.lonple.dap.server.modules.chat.mapper.ChatMessageMapper;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.position.mapper.PositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流水线内同步调用 Agent（收集完整回复，供平台解析确认 JSON）。
 * step 事件与工作台一致写入 ES，供「执行日志」折叠展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipeAgentExecutor {

    private final AgentClient agentClient;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final PositionMapper positionMapper;
    private final AgentFsToolService agentFsToolService;
    private final ChatStepRecorder chatStepRecorder;
    private final TokenUsageService tokenUsageService;
    private final PipeUnitRunRegistry unitRunRegistry;
    private final ObjectMapper objectMapper;

    public String run(WbChatSession session, String userContent) throws Exception {
        return run(session, userContent, null, List.of(), null);
    }

    public String run(WbChatSession session, String userContent, Long unitInstanceId) throws Exception {
        return run(session, userContent, unitInstanceId, List.of(), null);
    }

    public String run(
            WbChatSession session,
            String userContent,
            Long unitInstanceId,
            List<Map<String, String>> images) throws Exception {
        return run(session, userContent, unitInstanceId, images, null);
    }

    /**
     * @param images 本轮随 user 消息发给 LLM 的多模态图（data URL）；只挂当前请求，不落库
     * @param codingMeta 编码四阶段元数据（codingStage / codingTaskId / stageDeliverableName），写入 file_index
     */
    public String run(
            WbChatSession session,
            String userContent,
            Long unitInstanceId,
            List<Map<String, String>> images,
            Map<String, String> codingMeta) throws Exception {
        WbChatMessage userMsg = new WbChatMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setRole("user");
        userMsg.setContent(userContent);
        userMsg.setStatus("done");
        userMsg.setCreatedAt(LocalDateTime.now());
        userMsg.setUpdatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        WbChatMessage assistantMsg = new WbChatMessage();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setRole("assistant");
        assistantMsg.setContent("");
        assistantMsg.setStatus("streaming");
        assistantMsg.setCreatedAt(LocalDateTime.now());
        assistantMsg.setUpdatedAt(LocalDateTime.now());
        messageMapper.insert(assistantMsg);

        String runId = UUID.randomUUID().toString();
        ChatStepWriteContext stepCtx = chatStepRecorder.buildContext(session);
        stepCtx.setMessageId(assistantMsg.getId());
        stepCtx.setRunId(runId);
        if (unitInstanceId != null) {
            unitRunRegistry.register(unitInstanceId, session.getId(), assistantMsg.getId(), runId);
        }

        List<Map<String, Object>> history = buildHistory(session.getId(), assistantMsg.getId());
        attachImagesToLastUserMessage(history, images);

        Map<String, Object> agentBody = new HashMap<>();
        agentBody.put("run_id", runId);
        agentBody.put("tenant_id", session.getTenantId());
        agentBody.put("user_id", session.getUserId());
        agentBody.put("session_id", session.getId());
        agentBody.put("workspace_id", session.getWorkspaceId());
        SysPosition position = session.getPositionId() == null ? null : positionMapper.selectById(session.getPositionId());
        if (position != null) {
            agentBody.put("position", Map.of("id", position.getId(), "name", position.getName()));
        } else {
            agentBody.put("position", Map.of("id", 0, "name", ""));
        }
        agentBody.put("skills", List.of());
        agentBody.put("messages", history);
        agentBody.put("session_type", session.getSessionType());
        agentBody.put("output_dir_node_id", session.getOutputDirNodeId());
        try {
            Object index = agentFsToolService.buildIndex(session.getId());
            if (codingMeta != null && !codingMeta.isEmpty() && index instanceof com.lonple.dap.server.modules.agenttool.dto.AgentFileIndexVO vo) {
                if (StringUtils.hasText(codingMeta.get("codingStage"))) {
                    vo.setCodingStage(codingMeta.get("codingStage"));
                }
                if (StringUtils.hasText(codingMeta.get("codingTaskId"))) {
                    vo.setCodingTaskId(codingMeta.get("codingTaskId"));
                }
                if (StringUtils.hasText(codingMeta.get("stageDeliverableName"))) {
                    vo.setStageDeliverableName(codingMeta.get("stageDeliverableName"));
                }
                if (StringUtils.hasText(codingMeta.get("taskRemark"))) {
                    vo.setTaskRemark(codingMeta.get("taskRemark"));
                }
            }
            agentBody.put("file_index", index);
            agentBody.put("tools_enabled", true);
        } catch (Exception e) {
            log.warn("pipe agent file_index failed: {}", e.getMessage());
            agentBody.put("tools_enabled", false);
        }

        StringBuilder contentBuf = new StringBuilder();
        AtomicInteger writeCounter = new AtomicInteger(0);
        boolean stopped = false;
        Exception streamError = null;
        try {
            try {
                agentClient.streamChat(agentBody, (event, data) -> {
                    if (unitInstanceId != null && unitRunRegistry.isStopped(unitInstanceId)) {
                        return;
                    }
                    try {
                        if ("content".equals(event)) {
                            JsonNode node = objectMapper.readTree(data);
                            contentBuf.append(node.path("delta").asText(""));
                            // 周期性落库，流水线面板轮询可看到流式正文
                            if (contentBuf.length() - writeCounter.get() > 80) {
                                persistAssistant(assistantMsg.getId(), contentBuf.toString(), "streaming");
                                writeCounter.set(contentBuf.length());
                            }
                        } else if ("step".equals(event)) {
                            // 与工作台一致：写入 ES，执行日志可拉到「调用模型 / 搜索 / 读文件」等步骤
                            chatStepRecorder.recordStep(stepCtx, runId, assistantMsg.getId(), data);
                        } else if ("usage".equals(event)) {
                            tokenUsageService.recordRunUsage(session, runId, assistantMsg.getId(), data);
                        } else if ("done".equals(event) && unitInstanceId != null) {
                            JsonNode node = objectMapper.readTree(data);
                            if (node.path("cancelled").asBoolean(false)) {
                                unitRunRegistry.markStopped(unitInstanceId);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("pipe agent event handle failed: {}", e.getMessage());
                    }
                }, null);
            } catch (Exception e) {
                // 停止时 SSE 常以 IO 异常结束，仍按「用户停止」处理
                if (unitInstanceId != null && unitRunRegistry.isStopped(unitInstanceId)) {
                    stopped = true;
                } else {
                    streamError = e;
                }
            }
        } finally {
            // 必须在 remove 前读取 stopped，否则 stopUnit 的标记会丢
            if (unitInstanceId != null) {
                stopped = stopped || unitRunRegistry.isStopped(unitInstanceId);
                unitRunRegistry.remove(unitInstanceId);
            }
            chatStepRecorder.clearRun(runId);
        }

        String finalContent = contentBuf.toString();
        if (stopped) {
            persistAssistant(assistantMsg.getId(), finalContent, "stopped");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            throw new PipeUnitStoppedException();
        }
        if (streamError != null) {
            persistAssistant(assistantMsg.getId(), finalContent, "error");
            if (streamError instanceof RuntimeException re) {
                throw re;
            }
            throw streamError;
        }
        persistAssistant(assistantMsg.getId(), finalContent, "done");
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        return finalContent;
    }

    private void persistAssistant(Long messageId, String content, String status) {
        WbChatMessage m = messageMapper.selectById(messageId);
        if (m == null) {
            return;
        }
        m.setContent(content);
        m.setStatus(status);
        m.setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(m);
    }

    private List<Map<String, Object>> buildHistory(Long sessionId, Long excludeAssistantId) {
        // 与工作台对齐：最近 20 条、单条截断，减轻流水线会话膨胀（细粒度压缩在 dap-agent Context Governor）
        List<WbChatMessage> messages = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WbChatMessage>()
                        .eq(WbChatMessage::getSessionId, sessionId)
                        .ne(WbChatMessage::getId, excludeAssistantId)
                        .orderByDesc(WbChatMessage::getId)
                        .last("LIMIT 20"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            WbChatMessage m = messages.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("role", m.getRole());
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.length() > 8000) {
                content = content.substring(0, 8000) + "…(truncated)";
            }
            item.put("content", content);
            result.add(item);
        }
        return result;
    }

    private static void attachImagesToLastUserMessage(
            List<Map<String, Object>> history, List<Map<String, String>> images) {
        if (history == null || history.isEmpty() || images == null || images.isEmpty()) {
            return;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> item = history.get(i);
            if ("user".equals(item.get("role"))) {
                item.put("images", images);
                return;
            }
        }
    }

    /** 从模型输出中提取确认 JSON（优先最后一个 json 代码块 / 含 phase 的对象）。 */
    public JsonNode tryParseConfirmJson(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String text = content.trim();
        // ```json ... ```
        int fence = text.lastIndexOf("```");
        if (fence > 0) {
            int startFence = text.lastIndexOf("```", fence - 1);
            if (startFence >= 0) {
                String block = text.substring(startFence + 3, fence).trim();
                if (block.regionMatches(true, 0, "json", 0, 4)) {
                    block = block.substring(4).trim();
                }
                JsonNode n = readJson(block);
                if (n != null && n.has("phase")) {
                    return n;
                }
            }
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            JsonNode n = readJson(text.substring(first, last + 1));
            if (n != null && n.has("phase")) {
                return n;
            }
        }
        return null;
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
