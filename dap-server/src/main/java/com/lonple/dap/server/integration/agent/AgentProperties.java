package com.lonple.dap.server.integration.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.agent")
public class AgentProperties {

    private String baseUrl = "http://127.0.0.1:8202";
    private String internalToken = "dev-internal-token";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 300000;
}
