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
    private final String fixedIndex;

    public ChatStepIndexNames(ChatStepIndexProperties properties) {
        String env = properties.getStepIndexEnv();
        this.indexEnv = env == null || env.isBlank() ? "dev" : env.trim();
        String name = properties.getStepIndexName();
        this.fixedIndex = name == null || name.isBlank() ? null : name.trim();
        if (fixedIndex != null && (!fixedIndex.matches("[a-z0-9][a-z0-9_.-]*")
                || fixedIndex.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255)) {
            throw new IllegalArgumentException("dap.chat.step-index-name 必须是小写的具体索引或别名，不能包含通配符");
        }
    }

    public String index(Instant occurredAt) {
        if (fixedIndex != null) return fixedIndex;
        Instant t = occurredAt == null ? Instant.now() : occurredAt;
        return indexEnv + "-dap-chat-step-" + MONTH.format(t);
    }

    public String searchPattern() {
        if (fixedIndex != null) return fixedIndex;
        return indexEnv + "-dap-chat-step-*";
    }
}
