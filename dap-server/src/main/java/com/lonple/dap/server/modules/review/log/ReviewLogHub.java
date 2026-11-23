package com.lonple.dap.server.modules.review.log;

import com.lonple.dap.server.modules.review.store.JsonTaskStore;
import com.lonple.dap.server.modules.review.store.ReviewProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReviewLogHub {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ReviewProperties properties;

    public ReviewLogHub(ReviewProperties properties) {
        this.properties = properties;
    }

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(properties.getSse().getTimeoutMs());
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> {
            CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
            if (list != null) {
                list.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    public void broadcast(String taskId, LogEntry entry) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("log").data(entry));
            } catch (IOException ex) {
                emitter.complete();
                list.remove(emitter);
            }
        }
    }

    /** 通知前端清空本任务日志面板（重新执行评审）。 */
    public void broadcastReset(String taskId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("reset").data(Map.of(
                        "taskId", taskId,
                        "at", Instant.now().toString())));
            } catch (IOException ex) {
                emitter.complete();
                list.remove(emitter);
            }
        }
    }

    public void sendSnapshot(SseEmitter emitter, List<LogEntry> entries) throws IOException {
        for (LogEntry entry : entries) {
            emitter.send(SseEmitter.event().name("log").data(entry));
        }
        emitter.send(SseEmitter.event().name("ready").data(Map.of("count", entries.size())));
    }
}
