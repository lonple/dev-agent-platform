package com.lonple.dap.server.modules.requirement;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.requirement.dto.ApproverCandidatesVO;
import com.lonple.dap.server.modules.requirement.dto.RequirementHandlerAddRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementPlanRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementRejectRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementSaveRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementSubmitRequest;
import com.lonple.dap.server.modules.requirement.dto.RequirementVO;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.RequirePermission;
import com.lonple.dap.server.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    @GetMapping
    @RequirePermission("requirement:view")
    public ApiResponse<List<RequirementVO>> listMine() {
        return ApiResponse.ok(requirementService.listMine());
    }

    @GetMapping("/inbox")
    @RequirePermission("work:access")
    public ApiResponse<List<RequirementVO>> inbox() {
        return ApiResponse.ok(requirementService.listInbox());
    }

    @GetMapping("/workbench")
    @RequirePermission("work:access")
    public ApiResponse<List<RequirementVO>> workbench(
            @RequestParam(defaultValue = "todo") String scope) {
        return ApiResponse.ok(requirementService.listWorkbench(scope));
    }

    @GetMapping("/approver-candidates")
    @RequirePermission("requirement:view")
    public ApiResponse<ApproverCandidatesVO> approverCandidates(@RequestParam Long productId) {
        return ApiResponse.ok(requirementService.listApproverCandidates(productId));
    }

    @GetMapping("/{id}")
    public ApiResponse<RequirementVO> get(@PathVariable Long id) {
        assertCanViewRequirement();
        return ApiResponse.ok(requirementService.get(id));
    }

    @PostMapping
    @RequirePermission("requirement:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody RequirementSaveRequest request) {
        return ApiResponse.ok(Map.of("id", requirementService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("requirement:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody RequirementSaveRequest request) {
        requirementService.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("requirement:operate")
    public ApiResponse<Void> submit(
            @PathVariable Long id, @RequestBody(required = false) RequirementSubmitRequest request) {
        requirementService.submit(id, request == null ? new RequirementSubmitRequest() : request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("work:access")
    public ApiResponse<Void> approve(@PathVariable Long id) {
        requirementService.approve(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("work:access")
    public ApiResponse<Void> reject(@PathVariable Long id, @Valid @RequestBody RequirementRejectRequest request) {
        requirementService.reject(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/plan")
    @RequirePermission("work:access")
    public ApiResponse<Void> plan(@PathVariable Long id, @Valid @RequestBody RequirementPlanRequest request) {
        requirementService.submitPlan(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/develop")
    @RequirePermission("work:access")
    public ApiResponse<Void> develop(@PathVariable Long id) {
        requirementService.startDevelopment(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/handlers")
    @RequirePermission("work:access")
    public ApiResponse<Void> addHandlers(
            @PathVariable Long id, @Valid @RequestBody RequirementHandlerAddRequest request) {
        requirementService.addHandlers(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/handlers/{handlerId}")
    @RequirePermission("work:access")
    public ApiResponse<Void> removeHandler(@PathVariable Long id, @PathVariable Long handlerId) {
        requirementService.removeHandler(id, handlerId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("requirement:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requirementService.delete(id);
        return ApiResponse.ok();
    }

    private void assertCanViewRequirement() {
        LoginUser user = UserContext.require();
        if (!user.getPermissions().contains("requirement:view")
                && !user.getPermissions().contains("work:access")) {
            throw new BizException(403, "无权限查看需求");
        }
    }
}
