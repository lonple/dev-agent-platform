package com.lonple.dap.server.modules.sandbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxLeaseReaper {

    private final SandboxProperties props;
    private final SandboxLeaseService leaseService;

    @Scheduled(fixedDelayString = "${dap.sandbox.reclaim-interval-sec:60}000")
    public void reclaim() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            int n = leaseService.reclaimExpired();
            if (n > 0) {
                log.info("sandbox reclaimed expired leases={}", n);
            }
        } catch (Exception e) {
            log.warn("sandbox reclaim failed: {}", e.getMessage());
        }
    }
}
