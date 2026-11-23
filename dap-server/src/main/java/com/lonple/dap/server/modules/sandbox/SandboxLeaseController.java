package com.lonple.dap.server.modules.sandbox;

import com.lonple.dap.server.common.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/work/sandbox")
@RequiredArgsConstructor
public class SandboxLeaseController {

    private final SandboxLeaseService leaseService;
    private final SandboxProfileService profileService;

    @PostMapping("/resources/ensure")
    public ApiResponse<Map<String, Object>> ensureResource(@RequestBody EnsureReq req) {
        return ApiResponse.ok(profileService.ensureDefaultBundle(req.getProductId()));
    }

    @PostMapping("/leases")
    public ApiResponse<Map<String, Object>> acquire(@RequestBody AcquireBody body) {
        SandboxLeaseService.AcquireReq req = new SandboxLeaseService.AcquireReq();
        req.productId = body.getProductId();
        req.resourceCode = body.getResourceCode();
        req.profileCode = body.getProfileCode();
        req.integrationEnvCode = body.getIntegrationEnvCode();
        req.reqNo = body.getReqNo();
        req.sessionId = body.getSessionId();
        req.jobId = body.getJobId();
        req.worktreeHostPath = body.getWorktreeHostPath();
        req.purpose = body.getPurpose();
        return ApiResponse.ok(leaseService.acquire(req));
    }

    @DeleteMapping("/leases/{leaseId}")
    public ApiResponse<Map<String, Object>> release(@PathVariable Long leaseId) {
        return ApiResponse.ok(leaseService.release(leaseId));
    }

    @GetMapping("/leases")
    public ApiResponse<?> list(@RequestParam Long productId) {
        return ApiResponse.ok(leaseService.listActiveByProduct(productId));
    }

    @Data
    public static class EnsureReq {
        private Long productId;
        private String resourceCode;
    }

    @Data
    public static class AcquireBody {
        private Long productId;
        private String resourceCode;
        private String profileCode;
        private String integrationEnvCode;
        private String reqNo;
        private Long sessionId;
        private Long jobId;
        private String worktreeHostPath;
        private String purpose;
    }
}
