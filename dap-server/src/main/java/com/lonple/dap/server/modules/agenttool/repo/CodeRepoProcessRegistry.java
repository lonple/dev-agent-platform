package com.lonple.dap.server.modules.agenttool.repo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级受控进程登记（start_dev）；stop 仅杀本表内进程。
 */
@Slf4j
@Component
public class CodeRepoProcessRegistry {

    public record ManagedProcess(long pid, String action, String repoPath, Process process) {}

    private final ConcurrentHashMap<Long, List<ManagedProcess>> bySession = new ConcurrentHashMap<>();

    public void register(Long sessionId, String action, String repoPath, Process process) {
        if (sessionId == null || process == null) {
            return;
        }
        long pid = process.pid();
        bySession.compute(sessionId, (k, list) -> {
            List<ManagedProcess> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
            next.removeIf(m -> !m.process().isAlive());
            next.add(new ManagedProcess(pid, action, repoPath, process));
            return next;
        });
        log.info("code process registered sessionId={} pid={} action={}", sessionId, pid, action);
    }

    public List<Map<String, Object>> list(Long sessionId) {
        List<ManagedProcess> list = bySession.getOrDefault(sessionId, List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ManagedProcess m : list) {
            if (!m.process().isAlive()) {
                continue;
            }
            out.add(Map.of(
                    "pid", m.pid(),
                    "action", m.action(),
                    "repoPath", m.repoPath(),
                    "alive", true));
        }
        return out;
    }

    public int stopAll(Long sessionId) {
        List<ManagedProcess> list = bySession.getOrDefault(sessionId, List.of());
        int stopped = 0;
        Iterator<ManagedProcess> it = list.iterator();
        while (it.hasNext()) {
            ManagedProcess m = it.next();
            try {
                if (m.process().isAlive()) {
                    m.process().toHandle().descendants().forEach(ph -> {
                        try {
                            ph.destroy();
                        } catch (Exception ignored) {
                        }
                    });
                    m.process().destroy();
                    boolean exited = m.process().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!exited && m.process().isAlive()) {
                        m.process().destroyForcibly();
                    }
                    stopped++;
                }
            } catch (Exception e) {
                log.warn("stop process pid={} failed: {}", m.pid(), e.getMessage());
            }
        }
        bySession.remove(sessionId);
        return stopped;
    }
}
