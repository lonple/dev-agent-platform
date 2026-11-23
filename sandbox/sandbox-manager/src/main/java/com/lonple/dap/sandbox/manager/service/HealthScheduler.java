package com.lonple.dap.sandbox.manager.service;

import com.lonple.dap.sandbox.manager.client.SandboxClientHttp;
import com.lonple.dap.sandbox.manager.domain.NodeRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthScheduler {

    private final NodeRegistry nodeRegistry;
    private final SandboxClientHttp clientHttp;

    @Scheduled(fixedDelayString = "${sandbox.health-interval-ms:10000}")
    public void tick() {
        for (NodeRegistry.NodeRuntime node : nodeRegistry.all()) {
            try {
                Map<String, Object> h = clientHttp.getHealth(node.getClientBaseUrl());
                node.setOnline(true);
                Object dockerOk = h.get("dockerOk");
                node.setDockerOk(Boolean.TRUE.equals(dockerOk) || "true".equalsIgnoreCase(String.valueOf(dockerOk)));
                node.setDetail(String.valueOf(h));
                node.setLastHealthAt(Instant.now());
            } catch (Exception e) {
                node.setOnline(false);
                node.setDockerOk(false);
                node.setDetail(e.getMessage());
                node.setLastHealthAt(Instant.now());
                log.debug("node health failed code={} err={}", node.getCode(), e.getMessage());
            }
        }
    }
}
