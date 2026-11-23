package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.pipeline.dto.CodingProgressVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineConfirmRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineInstanceVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelinePmSecondRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStartRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineTrackMergeVO;
import com.lonple.dap.server.modules.pipeline.entity.PipeConfirmRound;
import com.lonple.dap.server.modules.pipeline.entity.PipeRepoWorktree;
import com.lonple.dap.server.modules.wiki.dto.WikiFileContentVO;
import com.lonple.dap.server.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipeRuntimeService pipeRuntimeService;
    private final PipeMergeService pipeMergeService;
    private final ObjectMapper objectMapper;

    @PostMapping("/instances")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> start(@Valid @RequestBody PipelineStartRequest request) {
        return ApiResponse.ok(Map.of("id", pipelineService.start(request)));
    }

    @GetMapping("/instances/by-requirement/{requirementId}")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> byRequirement(@PathVariable Long requirementId) {
        return ApiResponse.ok(pipelineService.getByRequirement(requirementId));
    }

    @GetMapping("/instances/by-issue/{issueId}")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> byIssue(@PathVariable Long issueId) {
        return ApiResponse.ok(pipelineService.getByIssue(issueId));
    }

    @GetMapping("/instances/{id}")
    @RequirePermission("work:access")
    public ApiResponse<PipelineInstanceVO> get(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.get(id));
    }

    @PostMapping("/instances/{id}/cancel")
    @RequirePermission("work:access")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        pipelineService.cancel(id);
        return ApiResponse.ok();
    }

    @PostMapping("/instances/{id}/pm-second")
    @RequirePermission("work:access")
    public ApiResponse<Void> pmSecond(@PathVariable Long id, @Valid @RequestBody PipelinePmSecondRequest request) {
        pipelineService.pmSecond(id, request);
        return ApiResponse.ok();
    }

    /** 手动补建 worktree（仅编码阶段需要；启动/设计阶段勿依赖） */
    @PostMapping("/instances/{id}/ensure-worktrees")
    @RequirePermission("work:access")
    public ApiResponse<List<PipeRepoWorktree>> ensureWorktrees(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.ensureWorktrees(id));
    }

    @GetMapping("/instances/{id}/docs")
    @RequirePermission("work:access")
    public ApiResponse<WikiFileContentVO> openDoc(
            @PathVariable Long id, @RequestParam("name") String name) {
        return ApiResponse.ok(pipelineService.openWikiDoc(id, name));
    }

    @PostMapping("/units/{unitId}/trigger")
    @RequirePermission("work:access")
    public ApiResponse<Void> triggerUnit(@PathVariable Long unitId) {
        pipeRuntimeService.triggerUnit(unitId);
        return ApiResponse.ok();
    }

    /** 开发编码：四阶段/任务卡进度与历史会话 */
    @GetMapping("/units/{unitId}/coding")
    @RequirePermission("work:access")
    public ApiResponse<CodingProgressVO> codingProgress(@PathVariable Long unitId) {
        return ApiResponse.ok(pipeRuntimeService.getCodingProgress(unitId));
    }

    /** 停止正在执行的 Agent 单元 */
    @PostMapping("/units/{unitId}/stop")
    @RequirePermission("work:access")
    public ApiResponse<Void> stopUnit(@PathVariable Long unitId) {
        pipeRuntimeService.stopUnit(unitId);
        return ApiResponse.ok();
    }

    @GetMapping("/units/{unitId}/confirm")
    @RequirePermission("work:access")
    public ApiResponse<PipeConfirmRound> getConfirm(@PathVariable Long unitId) {
        return ApiResponse.ok(pipeRuntimeService.getPendingConfirm(unitId));
    }

    @PostMapping("/units/{unitId}/confirm")
    @RequirePermission("work:access")
    public ApiResponse<Void> confirmUnit(@PathVariable Long unitId, @RequestBody PipelineConfirmRequest request) {
        pipeRuntimeService.confirmUnit(unitId, request);
        return ApiResponse.ok();
    }

    /** 完成输出后：手动将个人空间正式文档/确认归档发布到 Wiki */
    @PostMapping("/units/{unitId}/publish")
    @RequirePermission("work:access")
    public ApiResponse<Void> publishUnit(@PathVariable Long unitId) {
        pipeRuntimeService.publishUnitOutput(unitId);
        return ApiResponse.ok();
    }

    @GetMapping("/instances/{id}/track-merge/{trackCode}")
    @RequirePermission("work:access")
    public ApiResponse<PipelineTrackMergeVO> getTrackMerge(
            @PathVariable Long id, @PathVariable String trackCode) {
        return ApiResponse.ok(pipeMergeService.getForInstance(id, trackCode));
    }

    @PostMapping("/instances/{id}/track-merge/{trackCode}/confirm")
    @RequirePermission("work:access")
    public ApiResponse<Void> confirmTrackMerge(
            @PathVariable Long id,
            @PathVariable String trackCode,
            @RequestBody Map<String, Object> body) {
        Object items = body.get("itemsJson");
        if (items == null) {
            items = body.get("items");
        }
        String itemsJson;
        if (items == null) {
            itemsJson = "[]";
        } else if (items instanceof String s) {
            itemsJson = s;
        } else {
            try {
                itemsJson = objectMapper.writeValueAsString(items);
            } catch (Exception e) {
                itemsJson = String.valueOf(items);
            }
        }
        pipeRuntimeService.confirmTrackMerge(id, trackCode, itemsJson);
        return ApiResponse.ok();
    }
}
