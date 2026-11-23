package com.lonple.dap.server.modules.review.log;

import com.lonple.dap.server.modules.review.store.JsonTaskStore;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ReviewLogService {
    private final JsonTaskStore store;
    private final ReviewLogHub hub;

    public ReviewLogService(JsonTaskStore store, ReviewLogHub hub) {
        this.store = store;
        this.hub = hub;
    }

    /** 清空落盘日志并通知 SSE 客户端重置面板。 */
    public void clearForRerun(String taskId) {
        store.clearLogs(taskId);
        hub.broadcastReset(taskId);
    }

    public LogEntry info(String taskId, String step, String message) {
        return append(taskId, "INFO", step, message, null);
    }

    public LogEntry info(String taskId, String step, String message, Map<String, Object> detail) {
        return append(taskId, "INFO", step, message, detail);
    }

    public LogEntry warn(String taskId, String step, String message) {
        return append(taskId, "WARN", step, message, null);
    }

    public LogEntry warn(String taskId, String step, String message, Map<String, Object> detail) {
        return append(taskId, "WARN", step, message, detail);
    }

    public LogEntry error(String taskId, String step, String message) {
        return append(taskId, "ERROR", step, message, null);
    }

    public LogEntry error(String taskId, String step, String message, Map<String, Object> detail) {
        return append(taskId, "ERROR", step, message, detail);
    }

    private LogEntry append(String taskId, String level, String step, String message, Map<String, Object> detail) {
        LogEntry entry = store.appendLogEntry(taskId, level, step, message, detail);
        hub.broadcast(taskId, entry);
        return entry;
    }
}
