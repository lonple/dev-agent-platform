package com.lonple.dap.server.modules.agenttool;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.integration.agent.AgentProperties;
import com.lonple.dap.server.modules.agenttool.dto.CodeRepoDtos;
import com.lonple.dap.server.modules.agenttool.repo.CodeRepoToolService;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionCodeRepo;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Agent 编码工具内部 API：仓内 FS + 白名单动作 + 端口探测。
 */
@RestController
@RequestMapping("/api/internal/agent")
@RequiredArgsConstructor
public class InternalAgentCodeRepoController {

    private final CodeRepoToolService codeRepoToolService;
    private final AgentProperties agentProperties;

    @GetMapping("/sessions/{sessionId}/code-repos")
    public ApiResponse<List<Map<String, Object>>> listRepos(
            @PathVariable Long sessionId, HttpServletRequest request) {
        return withSessionContext(sessionId, request, () -> {
            List<WbSessionCodeRepo> repos = codeRepoToolService.listRepos(sessionId);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < repos.size(); i++) {
                WbSessionCodeRepo r = repos.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("repoIndex", i);
                m.put("localPath", r.getLocalPath());
                out.add(m);
            }
            return out;
        });
    }

    @PostMapping("/sessions/{sessionId}/code/list")
    public ApiResponse<CodeRepoDtos.ListResult> listDir(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.ListRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> codeRepoToolService.listDir(
                        sessionId, body.getRepoIndex(), body.getLocalPath(), body.getRelativePath()));
    }

    @PostMapping("/sessions/{sessionId}/code/read")
    public ApiResponse<CodeRepoDtos.ReadResult> readFile(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.PathRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> codeRepoToolService.readFile(
                        sessionId,
                        body.getRepoIndex(),
                        body.getLocalPath(),
                        body.getRelativePath(),
                        body.getStartLine(),
                        body.getEndLine(),
                        body.getMaxChars()));
    }

    @PostMapping("/sessions/{sessionId}/code/write")
    public ApiResponse<CodeRepoDtos.GenericResult> writeFile(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.WriteRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> codeRepoToolService.writeFile(
                        sessionId,
                        body.getRepoIndex(),
                        body.getLocalPath(),
                        body.getRelativePath(),
                        body.getContent()));
    }

    @PostMapping("/sessions/{sessionId}/code/delete")
    public ApiResponse<CodeRepoDtos.GenericResult> deleteFile(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.DeleteRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> codeRepoToolService.deleteFile(
                        sessionId, body.getRepoIndex(), body.getLocalPath(), body.getRelativePath()));
    }

    @PostMapping("/sessions/{sessionId}/code/search")
    public ApiResponse<Map<String, Object>> search(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.SearchRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> codeRepoToolService.searchInRepo(
                        sessionId, body.getRepoIndex(), body.getLocalPath(), body.getQuery()));
    }

    @PostMapping("/sessions/{sessionId}/code/action")
    public ApiResponse<CodeRepoDtos.ActionResult> action(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.ActionRequest body,
            HttpServletRequest request) {
        return withSessionContext(
                sessionId,
                request,
                () -> codeRepoToolService.runAction(
                        sessionId,
                        body.getRepoIndex(),
                        body.getLocalPath(),
                        body.getAction(),
                        body.getArgs()));
    }

    @PostMapping("/sessions/{sessionId}/code/check-port")
    public ApiResponse<CodeRepoDtos.PortResult> checkPort(
            @PathVariable Long sessionId,
            @Valid @RequestBody CodeRepoDtos.PortRequest body,
            HttpServletRequest request) {
        return withSessionContext(sessionId, request, () -> codeRepoToolService.checkPort(body.getPort()));
    }

    private <T> ApiResponse<T> withSessionContext(
            Long sessionId, HttpServletRequest request, Supplier<T> action) {
        assertInternalToken(request);
        WbChatSession session = codeRepoToolService.requireSession(sessionId);
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
        String token = request.getHeader(InternalAgentFsController.HEADER_INTERNAL);
        String expected = agentProperties.getInternalToken();
        if (!StringUtils.hasText(expected) || !expected.equals(token)) {
            throw new BizException(401, "内部凭证无效");
        }
    }
}
