package com.lonple.dap.server.modules.review.store;

import com.lonple.dap.server.common.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class TaskPaths {
    private final ReviewProperties properties;
    private Path root;

    public TaskPaths(ReviewProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() throws IOException {
        root = WorkspacePaths.resolveDataDir(properties.getDataDir());
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("tasks"));
        Files.createDirectories(root.resolve("code"));
        Path index = root.resolve("index.json");
        if (!Files.exists(index)) {
            Files.writeString(index, """
                    {"tasks":[]}
                    """);
        }
    }

    public Path root() {
        return root;
    }

    public Path indexFile() {
        return root.resolve("index.json");
    }

    public Path taskDir(String taskId) {
        return root.resolve("tasks").resolve(safe(taskId));
    }

    public Path configFile(String taskId) {
        return taskDir(taskId).resolve("config.json");
    }

    public Path logsFile(String taskId) {
        return taskDir(taskId).resolve("logs.json");
    }

    public Path resultFile(String taskId) {
        return taskDir(taskId).resolve("result.json");
    }

    public Path codeDir(String codeKey) {
        return root.resolve("code").resolve(safe(codeKey));
    }

    public void ensureTaskDir(String taskId) {
        try {
            Files.createDirectories(taskDir(taskId));
        } catch (IOException e) {
            throw new BizException(500, "无法创建任务目录: " + e.getMessage());
        }
    }

    private static String safe(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("非法路径名");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new BizException("非法路径名: " + name);
        }
        return name;
    }
}
