package com.lonple.dap.server.modules.pipeline;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 流水线单元正在执行的 Agent run，供「停止」打断。 */
@Component
public class PipeUnitRunRegistry {

    public record ActiveRun(
            Long unitId,
            Long sessionId,
            Long messageId,
            String runId,
            AtomicBoolean stopped) {
    }

    private final Map<Long, ActiveRun> byUnit = new ConcurrentHashMap<>();
    private final Map<String, ActiveRun> byRun = new ConcurrentHashMap<>();

    public void register(Long unitId, Long sessionId, Long messageId, String runId) {
        ActiveRun run = new ActiveRun(unitId, sessionId, messageId, runId, new AtomicBoolean(false));
        byUnit.put(unitId, run);
        byRun.put(runId, run);
    }

    public ActiveRun getByUnit(Long unitId) {
        return unitId == null ? null : byUnit.get(unitId);
    }

    public ActiveRun getByRun(String runId) {
        return runId == null ? null : byRun.get(runId);
    }

    public boolean markStopped(Long unitId) {
        ActiveRun run = getByUnit(unitId);
        if (run == null) {
            return false;
        }
        run.stopped().set(true);
        return true;
    }

    public boolean isStopped(Long unitId) {
        ActiveRun run = getByUnit(unitId);
        return run != null && run.stopped().get();
    }

    public void remove(Long unitId) {
        if (unitId == null) {
            return;
        }
        ActiveRun run = byUnit.remove(unitId);
        if (run != null) {
            byRun.remove(run.runId());
        }
    }
}
