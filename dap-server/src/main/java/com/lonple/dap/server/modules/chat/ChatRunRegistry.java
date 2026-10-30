package com.lonple.dap.server.modules.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ChatRunRegistry {

    public record ActiveRun(Long sessionId, Long messageId, AtomicBoolean stopped) {
    }

    private final Map<String, ActiveRun> runs = new ConcurrentHashMap<>();

    public void register(String runId, Long sessionId, Long messageId) {
        runs.put(runId, new ActiveRun(sessionId, messageId, new AtomicBoolean(false)));
    }

    public ActiveRun get(String runId) {
        return runs.get(runId);
    }

    public ActiveRun remove(String runId) {
        return runs.remove(runId);
    }

    public boolean markStopped(String runId) {
        ActiveRun run = runs.get(runId);
        if (run == null) {
            return false;
        }
        run.stopped().set(true);
        return true;
    }
}
