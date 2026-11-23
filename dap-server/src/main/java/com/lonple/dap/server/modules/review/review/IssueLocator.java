package com.lonple.dap.server.modules.review.review;

import com.lonple.dap.server.modules.review.partition.DiffFile;
import com.lonple.dap.server.modules.review.partition.ReviewShard;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用 diff 与源码把 issue 行号对齐。对不上的 HIGH/MEDIUM 不进入最终结果。
 */
@Component
public class IssueLocator {

    public List<ReviewIssue> locate(Path workTree, List<ReviewShard> shards, List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = allowedPaths(shards);
        List<ReviewIssue> kept = new ArrayList<>();
        for (ReviewIssue issue : issues) {
            ReviewIssue.Location loc = issue.getLocation();
            String path = loc == null ? "" : normalize(loc.getPath());
            if (!StringUtils.hasText(path) || !allowed.contains(path)) {
                issue.setLocated(false);
                issue.setLocateNote("路径不在本片 diff 或调用链上下文中");
                if (keepUnlocated(issue)) {
                    kept.add(issue);
                }
                continue;
            }
            String snippet = snippet(issue);
            int line = findLine(workTree, path, snippet, loc.getSymbol());
            if (line > 0) {
                loc.setPath(path);
                loc.setStartLine(line);
                if (loc.getEndLine() == null || loc.getEndLine() < line) {
                    loc.setEndLine(line);
                }
                issue.setLocated(true);
                issue.setLocateNote("已对齐到源码第 " + line + " 行");
                kept.add(issue);
            } else {
                issue.setLocated(false);
                issue.setLocateNote("证据片段未在文件中命中");
                if (keepUnlocated(issue)) {
                    kept.add(issue);
                }
            }
        }
        return kept;
    }

    private static boolean keepUnlocated(ReviewIssue issue) {
        String sev = issue.getSeverity() == null ? "" : issue.getSeverity().toUpperCase(Locale.ROOT);
        return "LOW".equals(sev);
    }

    static Set<String> allowedPaths(List<ReviewShard> shards) {
        Set<String> set = new LinkedHashSet<>();
        if (shards == null) {
            return set;
        }
        for (ReviewShard shard : shards) {
            for (DiffFile f : shard.files()) {
                set.add(normalize(f.path()));
            }
            for (String p : shard.contextPaths()) {
                set.add(normalize(p));
            }
        }
        return set;
    }

    private static int findLine(Path workTree, String rel, String snippet, String symbol) {
        Path file = workTree.resolve(rel).normalize();
        if (!file.startsWith(workTree) || !Files.isRegularFile(file)) {
            return -1;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return -1;
        }
        String[] lines = text.split("\n", -1);
        String needle = firstNeedle(snippet, symbol);
        if (!StringUtils.hasText(needle)) {
            return locStartOr(1);
        }
        String compactNeedle = compact(needle);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle) || compact(lines[i]).contains(compactNeedle)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int locStartOr(int n) {
        return n;
    }

    private static String snippet(ReviewIssue issue) {
        if (issue.getEvidence() != null && issue.getEvidence().length() >= 8) {
            return issue.getEvidence();
        }
        return issue.getLocation() == null ? "" : issue.getLocation().getSymbol();
    }

    private static String firstNeedle(String snippet, String symbol) {
        if (StringUtils.hasText(snippet)) {
            for (String line : snippet.split("\n")) {
                String t = line.strip();
                if (t.startsWith("```") || t.startsWith("//") && t.length() < 4) {
                    continue;
                }
                if (t.length() >= 8) {
                    return t.length() > 160 ? t.substring(0, 160) : t;
                }
            }
        }
        return symbol == null ? "" : symbol.trim();
    }

    private static String compact(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').replaceFirst("^\\./", "");
    }
}
