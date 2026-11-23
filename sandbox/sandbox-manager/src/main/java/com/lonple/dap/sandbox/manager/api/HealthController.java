package com.lonple.dap.sandbox.manager.api;

import com.lonple.dap.sandbox.manager.domain.NodeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {
    private final NodeRegistry nodeRegistry;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "nodes", nodeRegistry.all().size());
    }
}
