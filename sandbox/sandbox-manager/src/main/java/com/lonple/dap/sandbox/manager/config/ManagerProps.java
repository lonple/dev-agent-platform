package com.lonple.dap.sandbox.manager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class ManagerProps {
    private String sharedToken = "dev-sandbox-token";
    private long healthIntervalMs = 10000;
    private List<Node> nodes = new ArrayList<>();

    @Data
    public static class Node {
        private String code;
        private String clientBaseUrl;
    }
}
