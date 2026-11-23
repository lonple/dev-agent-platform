package com.lonple.dap.codetools.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Dto {
    private Dto() {}

    @Data
    public static class ListReq {
        private String relativePath = ".";
    }

    @Data
    public static class PathReq {
        @NotBlank
        private String relativePath;
        private Integer startLine;
        private Integer endLine;
        private Integer maxChars;
    }

    @Data
    public static class WriteReq {
        @NotBlank
        private String relativePath;
        private String content = "";
    }

    @Data
    public static class DeleteReq {
        @NotBlank
        private String relativePath;
    }

    @Data
    public static class SearchReq {
        @NotBlank
        private String query;
    }

    @Data
    public static class ActionReq {
        @NotBlank
        private String action;
        private Map<String, String> args = new HashMap<>();
    }

    @Data
    public static class CommitReq {
        @NotBlank
        private String message;
        private String authorName = "dap-sandbox";
        private String authorEmail = "sandbox@dap.local";
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
        private boolean background;
        private Long pid;
        private String stdout;
        private String stderr;
    }
}
