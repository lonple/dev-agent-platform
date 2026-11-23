package com.lonple.dap.server.modules.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.integration.agent.AgentClient;
import com.lonple.dap.server.integration.agent.ChatHistoryProperties;
import com.lonple.dap.server.modules.agenttool.AgentFsToolService;
import com.lonple.dap.server.modules.chat.dto.ChatCompletionRequest;
import com.lonple.dap.server.modules.chat.dto.ChatMessageVO;
import com.lonple.dap.server.modules.chat.dto.ChatSessionVO;
import com.lonple.dap.server.modules.chat.entity.WbChatMessage;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.audit.TokenUsageService;
import com.lonple.dap.server.modules.chat.es.ChatStepDocument;
import com.lonple.dap.server.modules.chat.es.ChatStepEsService;
import com.lonple.dap.server.modules.chat.es.ChatStepRecorder;
import com.lonple.dap.server.modules.chat.es.ChatStepWriteContext;
import com.lonple.dap.server.modules.chat.mapper.ChatMessageMapper;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.position.entity.SysPositionSkill;
import com.lonple.dap.server.modules.tenantcontext.TenantContextService;
import com.lonple.dap.server.modules.tenantcontext.entity.SysUserTenantContext;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final TenantContextService tenantContextService;
    private final PositionService positionService;
    private final AgentClient agentClient;
    private final AgentFsToolService agentFsToolService;
    private final ChatHistoryProperties historyProperties;
    private final ChatRunRegistry runRegistry;
    private final ObjectMapper objectMapper;
    private final ChatStepRecorder chatStepRecorder;
    private final ChatStepEsService chatStepEsService;
    private final TokenUsageService tokenUsageService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public List<ChatSessionVO> listSessions() {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        return sessionMapper.selectList(new LambdaQueryWrapper<WbChatSession>()
                        .eq(WbChatSession::getTenantId, tenantId)
                        .eq(WbChatSession::getUserId, userId)
                        .orderByDesc(WbChatSession::getUpdatedAt))
                .stream()
                .map(this::toSessionVO)
                .toList();
    }

    @Transactional
    public void deleteSession(Long id) {
        WbChatSession session = requireOwnedSession(id);
        messageMapper.delete(new LambdaQueryWrapper<WbChatMessage>()
                .eq(WbChatMessage::getSessionId, session.getId()));
        sessionMapper.deleteById(session.getId());
    }

    public List<ChatMessageVO> listMessages(Long sessionId) {
        requireOwnedSession(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<WbChatMessage>()
                        .eq(WbChatMessage::getSessionId, sessionId)
                        .orderByAsc(WbChatMessage::getId))
                .stream()
                .map(this::toMessageVO)
                .toList();
    }

    /** 工作台回放简要步骤；默认不含 detail */
    public List<ChatStepDocument> listSteps(Long sessionId, String runId, boolean includeDetail) {
        WbChatSession session = requireOwnedSession(sessionId);
        return chatStepEsService.listBySession(
                session.getTenantId(), sessionId, runId, includeDetail);
    }

    public SseEmitter completions(ChatCompletionRequest request) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        SysUserTenantContext ctx = tenantContextService.findOptional();
        Long positionId = ctx != null ? ctx.getPositionId() : null;
        SysPosition position = null;
        List<SysPositionSkill> skills = List.of();
        String customSkill = null;
        if (positionId != null) {
            try {
                position = positionService.requireOwned(positionId);
                if (ctx != null) {
                    skills = tenantContextService.listContextSkills(ctx.getId());
                    customSkill = ctx.getCustomSkillText();
                }
            } catch (BizException ignored) {
                position = null;
            }
        }
        log.info(
                "chat completions start tenantId={} userId={} sessionId={} positionId={} skills={} contentLen={}",
                tenantId,
                userId,
                request.getSessionId(),
                positionId,
                skills.size(),
                request.getContent() == null ? 0 : request.getContent().length());

        if (request.getSessionId() == null) {
            throw new BizException("请先在工作空间中创建会话");
        }
        WbChatSession session = requireOwnedSession(request.getSessionId());
        if (positionId != null && session.getPositionId() == null) {
            session.setPositionId(positionId);
            sessionMapper.updateById(session);
        }

        WbChatMessage userMsg = new WbChatMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setRole("user");
        userMsg.setContent(request.getContent());
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

        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        String runId = UUID.randomUUID().toString();
        runRegistry.register(runId, session.getId(), assistantMsg.getId());

        List<Map<String, Object>> history = buildHistory(session.getId(), assistantMsg.getId());

        Map<String, Object> agentBody = new HashMap<>();
        agentBody.put("run_id", runId);
        agentBody.put("tenant_id", tenantId);
        agentBody.put("user_id", userId);
        agentBody.put("session_id", session.getId());
        agentBody.put("workspace_id", session.getWorkspaceId());
        if (position != null) {
            agentBody.put("position", Map.of("id", position.getId(), "name", position.getName()));
        } else {
            agentBody.put("position", Map.of("id", 0, "name", ""));
        }
        List<Map<String, Object>> skillPayload = new ArrayList<>();
        for (SysPositionSkill skill : skills) {
            skillPayload.add(Map.of("id", skill.getId(), "name", skill.getName()));
        }
        if (StringUtils.hasText(customSkill)) {
            skillPayload.add(Map.of("custom", customSkill));
        }
        agentBody.put("skills", skillPayload);
        agentBody.put("messages", history);
        agentBody.put("session_type", session.getSessionType());
        agentBody.put("output_dir_node_id", session.getOutputDirNodeId());
        try {
            agentBody.put("file_index", agentFsToolService.buildIndex(session.getId()));
            agentBody.put("tools_enabled", true);
        } catch (Exception e) {
            log.warn("build file_index failed sessionId={}: {}", session.getId(), e.getMessage());
            agentBody.put("tools_enabled", false);
        }

        SseEmitter emitter = new SseEmitter(0L);
        Long sessionId = session.getId();
        Long assistantId = assistantMsg.getId();
        String userContent = request.getContent();
        boolean needTitle = "新对话".equals(session.getTitle()) || !StringUtils.hasText(session.getTitle());
        ChatStepWriteContext stepCtx = chatStepRecorder.buildContext(session);
        stepCtx.setMessageId(assistantId);
        stepCtx.setRunId(runId);

        emitter.onCompletion(() -> {
            runRegistry.remove(runId);
            chatStepRecorder.clearRun(runId);
        });
        emitter.onTimeout(() -> {
            runRegistry.markStopped(runId);
            agentClient.stop(runId);
        });

        executor.execute(() -> {
            StringBuilder contentBuf = new StringBuilder();
            AtomicInteger writeCounter = new AtomicInteger(0);
            try {
                sendEvent(emitter, "meta", Map.of(
                        "sessionId", sessionId,
                        "messageId", assistantId,
                        "runId", runId,
                        "clientTempId", request.getClientTempId() == null ? "" : request.getClientTempId()));

                agentClient.streamChat(agentBody, (event, data) -> {
                    ChatRunRegistry.ActiveRun run = runRegistry.get(runId);
                    if (run != null && run.stopped().get()) {
                        return;
                    }
                    try {
                        if ("content".equals(event)) {
                            JsonNode node = objectMapper.readTree(data);
                            String delta = node.path("delta").asText("");
                            contentBuf.append(delta);
                            if (contentBuf.length() - writeCounter.get() > 80) {
                                persistContent(assistantId, contentBuf.toString(), "streaming");
                                writeCounter.set(contentBuf.length());
                            }
                            sendRaw(emitter, "content", data);
                        } else if ("step".equals(event)) {
                            // 简要转前端；全量（含 detail）异步落 ES
                            sendRaw(emitter, "step", data);
                            chatStepRecorder.recordStep(stepCtx, runId, assistantId, data);
                        } else if ("usage".equals(event)) {
                            sendRaw(emitter, "usage", data);
                            tokenUsageService.recordRunUsage(session, runId, assistantId, data);
                        } else if ("error".equals(event)) {
                            sendRaw(emitter, "error", data);
                        } else if ("done".equals(event)) {
                            sendRaw(emitter, "done", data);
                        }
                    } catch (Exception e) {
                        log.warn("forward sse event failed: {}", e.getMessage());
                    }
                }, null);

                ChatRunRegistry.ActiveRun run = runRegistry.get(runId);
                boolean stopped = run != null && run.stopped().get();
                String finalStatus = stopped ? "stopped" : "done";
                persistContent(assistantId, contentBuf.toString(), finalStatus);

                if (needTitle && !stopped) {
                    String title = agentClient.generateTitle(userContent);
                    if (!StringUtils.hasText(title)) {
                        title = truncate(userContent, 30);
                    } else {
                        title = truncate(title, 30);
                    }
                    WbChatSession s = sessionMapper.selectById(sessionId);
                    if (s != null) {
                        s.setTitle(title);
                        s.setUpdatedAt(LocalDateTime.now());
                        sessionMapper.updateById(s);
                        sendEvent(emitter, "title", Map.of("sessionId", sessionId, "title", title));
                    }
                }

                emitter.complete();
            } catch (Exception e) {
                log.error("chat completion failed", e);
                persistContent(assistantId, contentBuf.toString(), "error");
                try {
                    sendEvent(emitter, "error", Map.of("message", e.getMessage() == null ? "Agent 错误" : e.getMessage()));
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                runRegistry.remove(runId);
                chatStepRecorder.clearRun(runId);
            }
        });

        return emitter;
    }

    public void stop(Long sessionId, String runId) {
        requireOwnedSession(sessionId);
        ChatRunRegistry.ActiveRun run = runRegistry.get(runId);
        if (run == null || !Objects.equals(run.sessionId(), sessionId)) {
            throw new BizException(400, "当前没有可停止的生成任务");
        }
        runRegistry.markStopped(runId);
        agentClient.stop(runId);
        persistContent(run.messageId(), null, "stopped");
    }

    private List<Map<String, Object>> buildHistory(Long sessionId, Long excludeAssistantId) {
        List<WbChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<WbChatMessage>()
                .eq(WbChatMessage::getSessionId, sessionId)
                .ne(WbChatMessage::getId, excludeAssistantId)
                .orderByDesc(WbChatMessage::getId)
                .last("LIMIT " + historyProperties.getHistoryMaxMessages()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            WbChatMessage m = messages.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("role", m.getRole());
            item.put("content", truncate(m.getContent(), historyProperties.getHistoryMaxChars()));
            if (StringUtils.hasText(m.getThinking())) {
                item.put("thinking", truncate(m.getThinking(), historyProperties.getHistoryMaxChars()));
            }
            result.add(item);
        }
        return result;
    }

    private void persistContent(Long messageId, String content, String status) {
        WbChatMessage msg = messageMapper.selectById(messageId);
        if (msg == null) {
            return;
        }
        if (content != null) {
            msg.setContent(content);
        }
        if (status != null) {
            msg.setStatus(status);
        }
        msg.setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(msg);
    }

    private WbChatSession requireOwnedSession(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        WbChatSession session = sessionMapper.selectById(id);
        if (session == null
                || !Objects.equals(session.getTenantId(), tenantId)
                || !Objects.equals(session.getUserId(), userId)) {
            throw new BizException(404, "会话不存在");
        }
        return session;
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) throws IOException {
        // 直接发对象，避免 String 被再次 JSON 转义导致前端拿不到 delta
        emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    }

    private void sendRaw(SseEmitter emitter, String event, String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        emitter.send(SseEmitter.event().name(event).data(node, MediaType.APPLICATION_JSON));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private ChatSessionVO toSessionVO(WbChatSession session) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(session.getId());
        vo.setWorkspaceId(session.getWorkspaceId());
        vo.setSessionType(session.getSessionType());
        vo.setPositionId(session.getPositionId());
        vo.setTitle(session.getTitle());
        vo.setRequirementId(session.getRequirementId());
        vo.setTaskTypeCode(session.getTaskTypeCode());
        if (session.getTaskTypeCode() != null) {
            try {
                SessionTaskType t = SessionTaskType.fromCode(session.getTaskTypeCode());
                vo.setTaskTypeName(t.getLabel());
                vo.setTaskTypeKind(t.getKind());
            } catch (Exception ignored) {
                // ignore
            }
        }
        vo.setOutputDirNodeId(session.getOutputDirNodeId());
        vo.setCreatedAt(session.getCreatedAt());
        vo.setUpdatedAt(session.getUpdatedAt());
        return vo;
    }

    private ChatMessageVO toMessageVO(WbChatMessage message) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setRole(message.getRole());
        vo.setThinking(message.getThinking());
        vo.setContent(message.getContent());
        vo.setStatus(message.getStatus());
        vo.setCreatedAt(message.getCreatedAt());
        vo.setUpdatedAt(message.getUpdatedAt());
        return vo;
    }
}
