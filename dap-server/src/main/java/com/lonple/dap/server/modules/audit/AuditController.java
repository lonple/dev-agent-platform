package com.lonple.dap.server.modules.audit;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.common.PageResult;
import com.lonple.dap.server.modules.audit.dto.TokenUsageStatVO;
import com.lonple.dap.server.modules.chat.es.ChatStepDocument;
import com.lonple.dap.server.modules.chat.es.ChatStepEsService;
import com.lonple.dap.server.security.RequirePermission;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final ChatStepEsService chatStepEsService;
    private final TokenUsageService tokenUsageService;

    @GetMapping("/chat-steps")
    @RequirePermission("audit:view")
    public ApiResponse<PageResult<ChatStepDocument>> chatSteps(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Long messageId,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String taskTypeCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDetail) {
        Long tenantId = TenantSupport.requireTenantId();
        return ApiResponse.ok(chatStepEsService.search(
                tenantId, page, size, from, to,
                sessionId, runId, messageId,
                requirementId, userId, taskTypeCode, keyword, includeDetail));
    }

    @GetMapping("/chat-steps/{eventId}")
    @RequirePermission("audit:view")
    public ApiResponse<ChatStepDocument> chatStep(@PathVariable String eventId) {
        ChatStepDocument doc = chatStepEsService.getByEventId(eventId);
        if (!Objects.equals(doc.getTenantId(), TenantSupport.requireTenantId())) {
            throw new BizException(404, "步骤日志不存在");
        }
        return ApiResponse.ok(doc);
    }

    @GetMapping("/token-usage/by-user")
    @RequirePermission("audit:view")
    public ApiResponse<List<TokenUsageStatVO>> tokenUsageByUser() {
        return ApiResponse.ok(tokenUsageService.statsByUser());
    }

    @GetMapping("/token-usage/by-requirement-user")
    @RequirePermission("audit:view")
    public ApiResponse<List<TokenUsageStatVO>> tokenUsageByRequirementUser() {
        return ApiResponse.ok(tokenUsageService.statsByRequirementUser());
    }

    @GetMapping("/token-usage/by-issue-user")
    @RequirePermission("audit:view")
    public ApiResponse<List<TokenUsageStatVO>> tokenUsageByIssueUser() {
        return ApiResponse.ok(tokenUsageService.statsByIssueUser());
    }
}
