package com.lonple.dap.server.modules.sandbox;

import tools.jackson.databind.ObjectMapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.agenttool.dto.CodeRepoDtos;
import com.lonple.dap.server.modules.sandbox.entity.SbLease;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 仅承载「变更类」仓操作转发。list/read/search 由本机 CodeRepoToolService 处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxCodeBridge {

    private final SandboxProperties props;
    private final SandboxLeaseService leaseService;
    private final SandboxManagerClient managerClient;
    private final ObjectMapper objectMapper;

    /** 是否应把变更类操作打到沙箱（需 enabled + 会话已 ATTACHED）。 */
    public boolean shouldRouteMutating(Long sessionId) {
        if (!props.isEnabled() || sessionId == null) {
            return false;
        }
        return leaseService.findAttachedBySession(sessionId) != null;
    }

    public CodeRepoDtos.GenericResult writeFile(Long sessionId, String relativePath, String content) {
        Long leaseId = leaseId(sessionId);
        int chars = content == null ? 0 : content.length();
        long t0 = System.currentTimeMillis();
        log.info("sandbox write lease={} path={} chars={}", leaseId, relativePath, chars);
        CodeRepoDtos.GenericResult out = convert(
                managerClient.code(leaseId, "write", Map.of(
                        "relativePath", relativePath == null ? "" : relativePath,
                        "content", content == null ? "" : content)),
                CodeRepoDtos.GenericResult.class);
        log.info("sandbox write done lease={} path={} costMs={}", leaseId, relativePath, System.currentTimeMillis() - t0);
        return out;
    }

    public CodeRepoDtos.GenericResult deleteFile(Long sessionId, String relativePath) {
        Long leaseId = leaseId(sessionId);
        long t0 = System.currentTimeMillis();
        log.info("sandbox delete lease={} path={}", leaseId, relativePath);
        Map<String, Object> body = new HashMap<>();
        body.put("relativePath", relativePath);
        CodeRepoDtos.GenericResult out = convert(managerClient.code(leaseId, "delete", body), CodeRepoDtos.GenericResult.class);
        log.info("sandbox delete done lease={} path={} costMs={}", leaseId, relativePath, System.currentTimeMillis() - t0);
        return out;
    }

    public CodeRepoDtos.ActionResult runAction(Long sessionId, String action, Map<String, String> args) {
        Long leaseId = leaseId(sessionId);
        long t0 = System.currentTimeMillis();
        log.info("sandbox action lease={} action={} argsKeys={}",
                leaseId, action, args == null ? java.util.Set.of() : args.keySet());
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        body.put("args", args == null ? Map.of() : args);
        CodeRepoDtos.ActionResult out = convert(managerClient.code(leaseId, "action", body), CodeRepoDtos.ActionResult.class);
        log.info("sandbox action done lease={} action={} costMs={}", leaseId, action, System.currentTimeMillis() - t0);
        return out;
    }

    public CodeRepoDtos.GenericResult commit(Long sessionId, String message, String authorName, String authorEmail) {
        Long leaseId = leaseId(sessionId);
        long t0 = System.currentTimeMillis();
        String msg = message == null ? "" : message.trim();
        String preview = msg.length() > 80 ? msg.substring(0, 80) + "…" : msg;
        log.info("sandbox commit lease={} message={}", leaseId, preview);
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        CodeRepoDtos.GenericResult out = convert(managerClient.code(leaseId, "commit", body), CodeRepoDtos.GenericResult.class);
        log.info("sandbox commit done lease={} costMs={}", leaseId, System.currentTimeMillis() - t0);
        return out;
    }

    private Long leaseId(Long sessionId) {
        SbLease lease = leaseService.requireAttachedBySession(sessionId);
        return lease.getId();
    }

    private <T> T convert(Map<String, Object> raw, Class<T> type) {
        try {
            return objectMapper.convertValue(raw, type);
        } catch (Exception e) {
            throw new BizException(502, "沙箱响应解析失败: " + e.getMessage());
        }
    }
}
