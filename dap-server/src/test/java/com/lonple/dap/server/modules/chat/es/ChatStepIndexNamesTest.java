package com.lonple.dap.server.modules.chat.es;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ChatStepIndexNamesTest {
    @Test
    void fixedTestIndexIsUsedForBothReadsAndWritesAcrossMonths() {
        var properties = new ChatStepIndexProperties();
        properties.setStepIndexEnv("it-dap-issue-123");
        properties.setStepIndexName("dap-test-chat-step");
        var names = new ChatStepIndexNames(properties);
        assertEquals("dap-test-chat-step", names.index(Instant.parse("2026-11-24T00:00:00Z")));
        assertEquals("dap-test-chat-step", names.index(Instant.parse("2026-12-24T00:00:00Z")));
        assertEquals("dap-test-chat-step", names.searchPattern());
    }

    @Test
    void existingMonthlyConfigurationStillWorksWhenNoFixedIndexIsSet() {
        var properties = new ChatStepIndexProperties();
        var names = new ChatStepIndexNames(properties);
        assertEquals("dev-dap-chat-step-2026.11", names.index(Instant.parse("2026-11-24T00:00:00Z")));
        assertEquals("dev-dap-chat-step-*", names.searchPattern());
    }

    @Test
    void wildcardAndUppercaseNamesAreRejectedBeforeWriting() {
        var properties = new ChatStepIndexProperties();
        for (String invalid : new String[]{"dev-*", "TEST", "_hidden", "index/name"}) {
            properties.setStepIndexName(invalid);
            assertThrows(IllegalArgumentException.class, () -> new ChatStepIndexNames(properties));
        }
    }
}
