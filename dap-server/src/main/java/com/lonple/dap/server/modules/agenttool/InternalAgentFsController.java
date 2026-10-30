package com.lonple.dap.server.modules.agenttool;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.integration.agent.AgentProperties;
import com.lonple.dap.server.modules.agenttool.dto.AgentFileIndexVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentReadRequest;
import com.lonple.dap.server.modules.agenttool.dto.AgentReadResultVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentSearchRequest;
import com.lonple.dap.server.modules.agenttool.dto.AgentSearchResultVO;
import com.lonple.dap.server.modules.agenttool.dto.AgentWriteRequest;
import com.lonple.dap.server.modules.agenttool.dto.AgentWriteResultVO;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Agent 进程调用的内部文档工具 API（X-Internal-Token，不走用户 JWT）。
 */
@RestController
@RequestMapping("/api/internal/agent")
@RequiredArgsConstructor
public class InternalAgentFsController {

    public static final String HEADER_INTERNAL = "X-Internal-Token";

    private final AgentFsToolService toolService;
    private final AgentProperties agentProperties;

    @GetMapping("/sessions/{sessionId}/file-index")
    public ApiResponse<AgentFileIndexVO> fileIndex(
            @PathVariable Long sessionId, HttpServletRequest request) {
        return withSessionContext(sessionId, request, () -> toolService.buildIndex(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/search")
    public ApiResponse<AgentSearchResultVO> search(
            @PathVariable Long sessionId,
            @Valid @RequestBody AgentSearchRequest body,
            HttpServletRequest request) {
        return withSessionContext(sessionId, request, () -> toolService.search(sessionId, body.getQuery()));
    }

    @PostMapping("/sessions/{sessionId}/read")
    public ApiResponse<AgentReadResultVO> read(
            @PathVariable Long sessionId,
            @Valid @RequestBody AgentReadRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> toolService.read(
                        sessionId, body.getSource(), body.getNodeId(), body.getStartLine(), body.getEndLine()));
    }

    @PostMapping("/sessions/{sessionId}/write")
    public ApiResponse<AgentWriteResultVO> write(
            @PathVariable Long sessionId,
            @Valid @RequestBody AgentWriteRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> toolService.write(sessionId, body.getDisplayName(), body.getContent()));
    }

    private <T> ApiResponse<T> withSessionContext(
            Long sessionId, HttpServletRequest request, Supplier<T> action) {
        assertInternalToken(request);
        WbChatSession session = toolService.requireSession(sessionId);
        LoginUser user = new LoginUser();
        user.setUserId(session.getUserId());
        user.setCurrentTenantId(session.getTenantId());
        user.setPermissions(Set.of("work:access"));
        UserContext.set(user);
        try {
            return ApiResponse.ok(action.get());
        } finally {
            UserContext.clear();
        }
    }

    private void assertInternalToken(HttpServletRequest request) {
        String token = request.getHeader(HEADER_INTERNAL);
        String expected = agentProperties.getInternalToken();
        if (!StringUtils.hasText(expected) || !expected.equals(token)) {
            throw new BizException(401, "内部凭证无效");
        }
    }
}
