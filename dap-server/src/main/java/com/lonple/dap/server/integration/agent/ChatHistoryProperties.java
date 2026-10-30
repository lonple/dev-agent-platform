package com.lonple.dap.server.integration.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.chat")
public class ChatHistoryProperties {

    private int historyMaxMessages = 20;
    private int historyMaxChars = 8000;
}
