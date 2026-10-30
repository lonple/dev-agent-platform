package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.pipeline.dto.PipelineAiPrepareVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineInstanceVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineReviewItemVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStartRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStepGateRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineUsageRequest;
import com.lonple.dap.server.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/requirements/{requirementId}/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    @GetMapping
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> get(@PathVariable Long requirementId) {
        return ApiResponse.ok(pipelineService.getByRequirement(requirementId));
    }

    @PostMapping("/start")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> start(
            @PathVariable Long requirementId,
            @RequestBody(required = false) PipelineStartRequest req) {
        return ApiResponse.ok(pipelineService.start(requirementId, req != null ? req : new PipelineStartRequest()));
    }

    @PostMapping("/cancel")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> cancel(@PathVariable Long requirementId) {
        return ApiResponse.ok(pipelineService.cancel(requirementId));
    }

    @PostMapping("/steps/{stepInstanceId}/approve")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> approve(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId,
            @RequestBody(required = false) PipelineStepGateRequest req) {
        return ApiResponse.ok(pipelineService.approveStep(requirementId, stepInstanceId, req));
    }

    @PostMapping("/steps/{stepInstanceId}/reject")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> reject(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId,
            @RequestBody(required = false) PipelineStepGateRequest req) {
        return ApiResponse.ok(pipelineService.rejectStep(requirementId, stepInstanceId, req));
    }

    /** AI 步骤完成并结算耗时；body 可带本轮 token（调试跳过用；正常由 Agent 自动完成） */
    @PostMapping("/steps/{stepInstanceId}/complete-ai")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> completeAi(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId,
            @RequestBody(required = false) PipelineUsageRequest usage) {
        return ApiResponse.ok(pipelineService.completeAiStep(requirementId, stepInstanceId, usage));
    }

    /** 回到待人工触发 */
    @PostMapping("/steps/{stepInstanceId}/retry-ai")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> retryAi(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId) {
        return ApiResponse.ok(pipelineService.retryAiStep(requirementId, stepInstanceId));
    }

    /** 人工触发：创建任务会话并返回 kickoff，前端再流式调用 Agent */
    @PostMapping("/steps/{stepInstanceId}/prepare-ai")
    @RequirePermission("work:access")
    public ApiResponse<PipelineAiPrepareVO> prepareAi(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId) {
        return ApiResponse.ok(pipelineService.prepareAi(requirementId, stepInstanceId));
    }

    /** 会话完成后落 Wiki 并推进 */
    @PostMapping("/steps/{stepInstanceId}/finalize-ai")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> finalizeAi(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId) {
        return ApiResponse.ok(pipelineService.finalizeAi(requirementId, stepInstanceId));
    }

    /** 确认人闸：更新有效评审项勾选 */
    @PutMapping("/steps/{stepInstanceId}/review-items")
    @RequirePermission("work:access")
    public ApiResponse<Void> updateReviewItems(
            @PathVariable Long requirementId,
            @PathVariable Long stepInstanceId,
            @RequestBody List<PipelineReviewItemVO> items) {
        pipelineService.updateReviewItems(requirementId, stepInstanceId, items);
        return ApiResponse.ok(null);
    }
}
