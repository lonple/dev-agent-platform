package com.lonple.dap.sandbox.manager.domain;

import com.lonple.dap.sandbox.manager.config.ManagerProps;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class NodeRegistry {

    private final ManagerProps props;
    private final Map<String, NodeRuntime> nodes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (ManagerProps.Node n : props.getNodes()) {
            if (n.getCode() == null || n.getClientBaseUrl() == null) {
                continue;
            }
            NodeRuntime rt = new NodeRuntime();
            rt.setCode(n.getCode());
            rt.setClientBaseUrl(n.getClientBaseUrl().replaceAll("/$", ""));
            rt.setOnline(false);
            nodes.put(rt.getCode(), rt);
        }
    }

    public Collection<NodeRuntime> all() {
        return nodes.values();
    }

    public NodeRuntime require(String code) {
        NodeRuntime n = nodes.get(code);
        if (n == null) {
            throw new IllegalArgumentException("unknown node: " + code);
        }
        return n;
    }

    public void upsert(String code, String clientBaseUrl) {
        NodeRuntime rt = nodes.computeIfAbsent(code, c -> new NodeRuntime());
        rt.setCode(code);
        rt.setClientBaseUrl(clientBaseUrl.replaceAll("/$", ""));
    }

    @Data
    public static class NodeRuntime {
        private String code;
        private String clientBaseUrl;
        private boolean online;
        private boolean dockerOk;
        private String detail;
        private Instant lastHealthAt;
    }
}
