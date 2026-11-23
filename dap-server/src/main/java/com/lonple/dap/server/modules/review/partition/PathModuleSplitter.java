package com.lonple.dap.server.modules.review.partition;

import com.lonple.dap.server.modules.review.store.ReviewProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 路径启发式模块切分：剥常见前缀后取 depth 段作为 moduleKey。
 */
@Component
public class PathModuleSplitter {
    private static final String[] STRIP_PREFIXES = {
            "src/main/java/",
            "src/main/resources/",
            "src/main/webapp/",
            "src/test/java/",
            "src/test/resources/",
            "src/modules/",
            "ada-front/src/",
            "ada-front/",
            "frontend/src/",
            "frontend/",
            "backend/src/",
            "backend/",
    };

    private final ReviewProperties properties;

    public PathModuleSplitter(ReviewProperties properties) {
        this.properties = properties;
    }

    public String moduleKey(String path) {
        if (!StringUtils.hasText(path)) {
            return "_default";
        }
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        String lower = p.toLowerCase(Locale.ROOT);
        for (String prefix : STRIP_PREFIXES) {
            if (lower.startsWith(prefix)) {
                p = p.substring(prefix.length());
                lower = p.toLowerCase(Locale.ROOT);
                break;
            }
            int idx = lower.indexOf('/' + prefix);
            if (idx >= 0) {
                p = p.substring(idx + 1 + prefix.length());
                lower = p.toLowerCase(Locale.ROOT);
                break;
            }
        }

        // 再剥包名前的 com/org 等
        p = stripPackageNoise(p);

        int depth = Math.max(1, properties.getPartition().getModuleDepth());
        String[] parts = p.split("/");
        List<String> meaningful = new ArrayList<>();
        for (String part : parts) {
            if (!StringUtils.hasText(part) || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            // 跳过纯文件名（最后一段带点）—— module 用目录段
            meaningful.add(part);
        }
        if (meaningful.isEmpty()) {
            return "_default";
        }
        // 若最后一段像文件，去掉
        if (meaningful.size() > 1 && meaningful.get(meaningful.size() - 1).contains(".")) {
            meaningful.remove(meaningful.size() - 1);
        }
        if (meaningful.isEmpty()) {
            return "_default";
        }
        int take = Math.min(depth, meaningful.size());
        // 对 Java 包：若前两段是 com/xxx，取业务段（depth 段从有效业务目录起）
        int start = 0;
        if (meaningful.size() >= 3
                && ("com".equalsIgnoreCase(meaningful.get(0))
                || "org".equalsIgnoreCase(meaningful.get(0))
                || "net".equalsIgnoreCase(meaningful.get(0)))) {
            // com.company.module... → 从第 3 段起取 depth
            start = Math.min(2, meaningful.size() - 1);
        }
        int end = Math.min(start + take, meaningful.size());
        if (start >= end) {
            return sanitize(meaningful.get(0));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(sanitize(meaningful.get(i)));
        }
        return sb.length() == 0 ? "_default" : sb.toString();
    }

    public Map<String, List<DiffFile>> groupByModule(List<DiffFile> files) {
        Map<String, List<DiffFile>> map = new LinkedHashMap<>();
        for (DiffFile f : files) {
            String key = moduleKey(f.path());
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        return map;
    }

    private static String stripPackageNoise(String p) {
        return p;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }
}
