package com.lonple.dap.server.modules.prompt;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.prompt.dto.PromptSaveRequest;
import com.lonple.dap.server.modules.prompt.dto.PromptVO;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.RequirePermission;
import com.lonple.dap.server.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    @GetMapping
    public ApiResponse<List<PromptVO>> list() {
        assertCanView();
        return ApiResponse.ok(promptService.list());
    }

    /** 按岗位 + 任务类型解析（工作台任务会话用） */
    @GetMapping("/resolve")
    public ApiResponse<PromptVO> resolve(
            @RequestParam Long positionId, @RequestParam String taskTypeCode) {
        assertCanView();
        return ApiResponse.ok(promptService.findByPositionAndTaskType(positionId, taskTypeCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<PromptVO> get(@PathVariable Long id) {
        assertCanView();
        return ApiResponse.ok(promptService.get(id));
    }

    @PostMapping
    @RequirePermission("prompt:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody PromptSaveRequest request) {
        return ApiResponse.ok(Map.of("id", promptService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("prompt:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody PromptSaveRequest request) {
        promptService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("prompt:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        promptService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 初始化流水线默认提示词。
     * 所有任务已配置则跳过，不覆盖租户维护的正文。
     */
    @PostMapping("/ensure-pre-review")
    @RequirePermission("prompt:operate")
    public ApiResponse<Map<String, Object>> ensurePreReview() {
        return ApiResponse.ok(promptService.ensurePreReviewPrompts());
    }

    private void assertCanView() {
        LoginUser user = UserContext.require();
        if (!user.getPermissions().contains("prompt:view")
                && !user.getPermissions().contains("work:access")) {
            throw new BizException(403, "无权限查看提示词");
        }
    }
}
