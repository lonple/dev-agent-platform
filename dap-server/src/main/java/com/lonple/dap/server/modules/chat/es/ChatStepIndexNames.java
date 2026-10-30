package com.lonple.dap.server.modules.chat.es;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class ChatStepIndexNames {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy.MM")
            .withZone(ZoneOffset.UTC);

    private final String indexEnv;

    public ChatStepIndexNames(ChatStepIndexProperties properties) {
        String env = properties.getStepIndexEnv();
        this.indexEnv = env == null || env.isBlank() ? "dev" : env.trim();
    }

    public String index(Instant occurredAt) {
        Instant t = occurredAt == null ? Instant.now() : occurredAt;
        return indexEnv + "-dap-chat-step-" + MONTH.format(t);
    }

    public String searchPattern() {
        return indexEnv + "-dap-chat-step-*";
    }
}
