package com.lonple.dap.server.modules.sandbox;

import com.lonple.dap.server.modules.pipeline.entity.PipeRepoWorktree;
import com.lonple.dap.server.modules.sandbox.entity.SbLease;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 流水线编码/IT 节点：自动申请 / 释放沙箱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxPipelineSupport {

    private final SandboxProperties props;
    private final SandboxLeaseService leaseService;

    public Map<String, Object> acquireForCoding(
            Long productId,
            String reqNo,
            Long sessionId,
            Long jobId,
            String purpose,
            String profileCode,
            String integrationEnvCode,
            List<PipeRepoWorktree> worktrees) {
        if (!props.isEnabled() || !props.isAutoAcquireOnCoding()) {
            return Map.of("skipped", true, "reason", "sandbox disabled or autoAcquire off");
        }
        String path = firstWorktreePath(worktrees);
        if (!StringUtils.hasText(path)) {
            log.warn("sandbox auto-acquire skipped: no worktree path productId={}", productId);
            return Map.of("skipped", true, "reason", "no worktree");
        }
        SandboxLeaseService.AcquireReq req = new SandboxLeaseService.AcquireReq();
        req.productId = productId;
        req.reqNo = reqNo;
        req.sessionId = sessionId;
        req.jobId = jobId;
        req.purpose = StringUtils.hasText(purpose) ? purpose : "PIPE_CODE";
        req.profileCode = profileCode;
        req.integrationEnvCode = integrationEnvCode;
        req.worktreeHostPath = path;
        return leaseService.acquire(req);
    }

    public void releaseForCoding(Long sessionId, Long jobId) {
        if (!props.isEnabled() || !props.isAutoReleaseOnCodingDone()) {
            return;
        }
        SbLease lease = leaseService.findAttachedBySession(sessionId);
        if (lease == null) {
            lease = leaseService.findAttachedByJob(jobId);
        }
        if (lease == null) {
            return;
        }
        try {
            // 只关 Docker 沙箱，不删除 worktree
            leaseService.release(lease.getId());
        } catch (Exception e) {
            log.warn("sandbox auto-release failed lease={}: {}", lease.getId(), e.getMessage());
        }
    }

    /** 等待人工确认：空闲 N 分钟后再回收沙箱；worktree 保留。 */
    public void armConfirmIdleRelease(Long sessionId) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            leaseService.armConfirmIdleRelease(sessionId, props.getConfirmIdleReleaseMinutes());
        } catch (Exception e) {
            log.warn("sandbox confirm-idle arm failed session={}: {}", sessionId, e.getMessage());
        }
    }

    private static String firstWorktreePath(List<PipeRepoWorktree> worktrees) {
        if (worktrees == null) {
            return null;
        }
        for (PipeRepoWorktree w : worktrees) {
            if (w != null && StringUtils.hasText(w.getWorktreePath())) {
                return w.getWorktreePath();
            }
        }
        return null;
    }
}
