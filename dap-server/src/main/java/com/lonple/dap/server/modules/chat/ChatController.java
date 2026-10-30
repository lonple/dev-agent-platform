package com.lonple.dap.server.modules.chat;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.chat.dto.ChatCompletionRequest;
import com.lonple.dap.server.modules.chat.dto.ChatMessageVO;
import com.lonple.dap.server.modules.chat.dto.ChatSessionVO;
import com.lonple.dap.server.modules.chat.dto.ChatStopRequest;
import com.lonple.dap.server.modules.chat.es.ChatStepDocument;
import com.lonple.dap.server.security.RequirePermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/sessions")
    @RequirePermission("work:access")
    public ApiResponse<List<ChatSessionVO>> sessions() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @DeleteMapping("/sessions/{id}")
    @RequirePermission("work:access")
    public ApiResponse<Void> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id);
        return ApiResponse.ok();
    }

    @GetMapping("/sessions/{id}/messages")
    @RequirePermission("work:access")
    public ApiResponse<List<ChatMessageVO>> messages(@PathVariable Long id) {
        return ApiResponse.ok(chatService.listMessages(id));
    }

    @GetMapping("/sessions/{id}/steps")
    @RequirePermission("work:access")
    public ApiResponse<List<ChatStepDocument>> steps(
            @PathVariable Long id,
            @RequestParam(required = false) String runId,
            @RequestParam(defaultValue = "false") boolean includeDetail) {
        return ApiResponse.ok(chatService.listSteps(id, runId, includeDetail));
    }

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("work:access")
    public SseEmitter completions(
            @Valid @RequestBody ChatCompletionRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        return chatService.completions(request);
    }

    @PostMapping("/sessions/{id}/stop")
    @RequirePermission("work:access")
    public ApiResponse<Void> stop(@PathVariable Long id, @Valid @RequestBody ChatStopRequest request) {
        chatService.stop(id, request.getRunId());
        return ApiResponse.ok();
    }
}
