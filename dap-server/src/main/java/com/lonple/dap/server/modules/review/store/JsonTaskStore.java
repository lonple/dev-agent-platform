package com.lonple.dap.server.modules.review.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.review.log.LogEntry;
import com.lonple.dap.server.modules.review.log.LogFile;
import com.lonple.dap.server.modules.review.review.model.ReviewResult;
import com.lonple.dap.server.modules.review.task.TaskConfig;
import com.lonple.dap.server.modules.review.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class JsonTaskStore {
    private final TaskPaths paths;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public JsonTaskStore(TaskPaths paths) {
        this.paths = paths;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public List<IndexItem> listIndex() {
        IndexFile index = readIndex();
        return index.getTasks() == null ? List.of() : new ArrayList<>(index.getTasks());
    }

    public TaskConfig loadConfig(String taskId) {
        Path file = paths.configFile(taskId);
        if (!Files.exists(file)) {
            throw new BizException(404, "任务不存在: " + taskId);
        }
        return withLock(taskId, () -> read(file, TaskConfig.class));
    }

    public void saveConfig(TaskConfig config) {
        withLock(config.getId(), () -> {
            paths.ensureTaskDir(config.getId());
            config.setUpdatedAt(Instant.now().toString());
            write(paths.configFile(config.getId()), config);
            upsertIndex(config);
            return null;
        });
    }

    public LogFile loadLogs(String taskId) {
        Path file = paths.logsFile(taskId);
        if (!Files.exists(file)) {
            return new LogFile();
        }
        return withLock(taskId, () -> read(file, LogFile.class));
    }

    public void saveLogs(String taskId, LogFile logFile) {
        withLock(taskId, () -> {
            paths.ensureTaskDir(taskId);
            write(paths.logsFile(taskId), logFile);
            return null;
        });
    }

    /** 单锁内追加一条日志，供并行分片安全写。 */
    public LogEntry appendLogEntry(String taskId, String level, String step, String message, Map<String, Object> detail) {
        return withLock(taskId, () -> {
            paths.ensureTaskDir(taskId);
            Path file = paths.logsFile(taskId);
            LogFile logFile = Files.exists(file) ? read(file, LogFile.class) : new LogFile();
            LogEntry entry = new LogEntry();
            entry.setId(logFile.getNextId());
            logFile.setNextId(logFile.getNextId() + 1);
            entry.setTime(Instant.now().toString());
            entry.setLevel(level);
            entry.setStep(step);
            entry.setMessage(message);
            entry.setDetail(detail);
            logFile.getEntries().add(entry);
            write(file, logFile);
            return entry;
        });
    }

    /** 清空任务日志（重新执行评审时调用）。 */
    public void clearLogs(String taskId) {
        withLock(taskId, () -> {
            paths.ensureTaskDir(taskId);
            write(paths.logsFile(taskId), new LogFile());
            return null;
        });
    }

    public ReviewResult loadResult(String taskId) {
        Path file = paths.resultFile(taskId);
        if (!Files.exists(file)) {
            return null;
        }
        return withLock(taskId, () -> read(file, ReviewResult.class));
    }

    public void saveResult(String taskId, ReviewResult result) {
        withLock(taskId, () -> {
            paths.ensureTaskDir(taskId);
            write(paths.resultFile(taskId), result);
            return null;
        });
    }

    public void deleteTask(String taskId) {
        withLock(taskId, () -> {
            Path dir = paths.taskDir(taskId);
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException e) {
                    throw new BizException(500, "删除任务失败: " + e.getMessage());
                }
            }
            IndexFile index = readIndex();
            if (index.getTasks() != null) {
                index.getTasks().removeIf(t -> taskId.equals(t.getId()));
                write(paths.indexFile(), index);
            }
            return null;
        });
    }

    private void upsertIndex(TaskConfig config) {
        IndexFile index = readIndex();
        if (index.getTasks() == null) {
            index.setTasks(new ArrayList<>());
        }
        index.getTasks().removeIf(t -> config.getId().equals(t.getId()));
        IndexItem item = new IndexItem();
        item.setId(config.getId());
        item.setName(config.getName());
        item.setStatus(config.getStatus() == null ? TaskStatus.DRAFT.name() : config.getStatus().name());
        item.setProductName(config.getProductName());
        item.setUpdatedAt(config.getUpdatedAt());
        index.getTasks().add(0, item);
        write(paths.indexFile(), index);
    }

    private IndexFile readIndex() {
        try {
            if (!Files.exists(paths.indexFile())) {
                return new IndexFile();
            }
            return mapper.readValue(paths.indexFile().toFile(), IndexFile.class);
        } catch (IOException e) {
            throw new BizException(500, "读取 index 失败: " + e.getMessage());
        }
    }

    private <T> T read(Path file, Class<T> type) {
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new BizException(500, "读取失败 " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private void write(Path file, Object value) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new BizException(500, "写入失败 " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private <T> T withLock(String taskId, IoSupplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }

    @lombok.Data
    public static class IndexFile {
        private List<IndexItem> tasks = new ArrayList<>();
    }

    @lombok.Data
    public static class IndexItem {
        private String id;
        private String name;
        private String status;
        private String productName;
        private String updatedAt;
    }
}
