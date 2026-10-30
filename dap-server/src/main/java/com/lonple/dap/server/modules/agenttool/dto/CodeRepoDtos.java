package com.lonple.dap.server.modules.agenttool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodeRepoDtos {

    private CodeRepoDtos() {}

    @Data
    public static class RepoRef {
        /** 仓库序号，从 0 开始，对应会话 codeRepos 列表下标；也可用 localPath */
        private Integer repoIndex;
        private String localPath;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ListRequest extends RepoRef {
        private String relativePath = ".";
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PathRequest extends RepoRef {
        @NotBlank
        private String relativePath;
        private Integer startLine;
        private Integer endLine;
        private Integer maxChars;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class WriteRequest extends RepoRef {
        @NotBlank
        private String relativePath;
        @NotNull
        private String content;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DeleteRequest extends RepoRef {
        @NotBlank
        private String relativePath;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class SearchRequest extends RepoRef {
        @NotBlank
        private String query;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ActionRequest extends RepoRef {
        @NotBlank
        private String action;
        /** 可选额外参数，如 git_diff 的相对路径、pytest 子路径 */
        private Map<String, String> args = new HashMap<>();
    }

    @Data
    public static class PortRequest {
        @NotNull
        @Min(1)
        @Max(65535)
        private Integer port;
    }

    @Data
    public static class GenericResult {
        private boolean ok = true;
        private String message;
        private Map<String, Object> data = new HashMap<>();
    }

    @Data
    public static class ListResult {
        private String repoPath;
        private String relativePath;
        private List<Entry> entries = new ArrayList<>();

        @Data
        public static class Entry {
            private String name;
            private String relativePath;
            private boolean directory;
            private Long size;
        }
    }

    @Data
    public static class ReadResult {
        private String repoPath;
        private String relativePath;
        private String content;
        private Integer startLine;
        private Integer endLine;
        private boolean truncated;
    }

    @Data
    public static class ActionResult {
        private String action;
        private String repoPath;
        private int exitCode;
        private boolean timedOut;
        private String stdout;
        private String stderr;
        private Long pid;
        private boolean background;
    }

    @Data
    public static class PortListener {
        private long pid;
        private String protocol;
        private String localAddress;
        private String imageName;
    }

    @Data
    public static class PortResult {
        private int port;
        private boolean inUse;
        private boolean canBind;
        private String message;
        /** netstat / lsof 看到的监听进程 */
        private List<PortListener> listeners = new ArrayList<>();
    }
}
