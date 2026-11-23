package com.lonple.dap.server.modules.review.partition;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 {@code git diff} 原文拆成按文件的 {@link DiffFile} 列表（不截断）。
 */
public final class DiffParser {
    /** 兼容普通路径与带空格/非 ASCII 时的引号形式：diff --git "a/..." "b/..." */
    private static final Pattern DIFF_GIT = Pattern.compile(
            "^diff --git (?:\"a/([^\"]+)\"|a/(.+?)) (?:\"b/([^\"]+)\"|b/(.+))$");

    private DiffParser() {
    }

    public static List<DiffFile> parse(String rawDiff) {
        List<DiffFile> out = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return out;
        }
        String[] lines = rawDiff.split("\\R", -1);
        StringBuilder current = null;
        String path = null;
        for (String line : lines) {
            Matcher m = DIFF_GIT.matcher(line);
            if (m.matches()) {
                flush(out, path, current);
                String a = firstNonNull(m.group(1), m.group(2));
                String b = firstNonNull(m.group(3), m.group(4));
                path = preferPath(a, b);
                current = new StringBuilder();
                current.append(line).append('\n');
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        flush(out, path, current);
        return out;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static void flush(List<DiffFile> out, String path, StringBuilder current) {
        if (current == null || path == null) {
            return;
        }
        String patch = current.toString();
        out.add(new DiffFile(path, patch, patch.length()));
    }

    private static String preferPath(String a, String b) {
        if (b != null && !"/dev/null".equals(b) && !"dev/null".equals(b)) {
            return b;
        }
        if (a != null && !"/dev/null".equals(a) && !"dev/null".equals(a)) {
            return a;
        }
        return b != null ? b : (a != null ? a : "unknown");
    }
}
