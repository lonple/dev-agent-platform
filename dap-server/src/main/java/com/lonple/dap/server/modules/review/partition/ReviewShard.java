package com.lonple.dap.server.modules.review.partition;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Worker 评审单元。
 */
public record ReviewShard(
        String shardId,
        ShardKind kind,
        String moduleKey,
        List<DiffFile> files,
        String diffText,
        List<String> contextPaths
) {
    public ReviewShard(String shardId, ShardKind kind, String moduleKey, List<DiffFile> files, String diffText) {
        this(shardId, kind, moduleKey, files, diffText, List.of());
    }

    public ReviewShard {
        files = files == null ? List.of() : List.copyOf(files);
        contextPaths = contextPaths == null ? List.of() : List.copyOf(contextPaths);
        if (diffText == null) {
            diffText = joinPatches(files);
        }
        if (moduleKey == null || moduleKey.isBlank()) {
            moduleKey = "_default";
        }
    }

    public ReviewShard withContext(List<String> paths) {
        return new ReviewShard(shardId, kind, moduleKey, files, diffText, paths);
    }

    public int fileCount() {
        return files.size();
    }

    public int charCount() {
        return diffText == null ? 0 : diffText.length();
    }

    public List<String> paths() {
        List<String> out = new ArrayList<>(files.size());
        for (DiffFile f : files) {
            out.add(f.path());
        }
        return out;
    }

    public static String joinPatches(List<DiffFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DiffFile f : files) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(f.patch());
            if (!f.patch().endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
