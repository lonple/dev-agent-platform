package com.lonple.dap.server.modules.review.partition;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把同一次 diff 里的接口、DTO 和前端 API 打成契约包，避免只按文件类型拆开评。
 */
@Component
public class ChangeBundler {
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern URL = Pattern.compile("[\"'`](/[a-zA-Z0-9_./{}\\-]+)[\"'`]");

    public List<ReviewShard> repartition(List<ReviewShard> shards) {
        if (shards == null || shards.isEmpty()) {
            return List.of();
        }
        List<DiffFile> all = new ArrayList<>();
        for (ReviewShard shard : shards) {
            all.addAll(shard.files());
        }
        List<List<DiffFile>> groups = groups(all);
        Set<String> bundled = new LinkedHashSet<>();
        List<ReviewShard> out = new ArrayList<>();
        int n = 0;
        for (List<DiffFile> group : groups) {
            n++;
            for (DiffFile f : group) {
                bundled.add(norm(f.path()));
            }
            String id = "bundle-" + n + "-" + stem(group.get(0).path());
            ShardKind kind = dominant(group);
            out.add(new ReviewShard(id, kind, "bundle", group, ReviewShard.joinPatches(group)));
        }
        for (ReviewShard shard : shards) {
            List<DiffFile> left = new ArrayList<>();
            for (DiffFile f : shard.files()) {
                if (!bundled.contains(norm(f.path()))) {
                    left.add(f);
                }
            }
            if (!left.isEmpty()) {
                out.add(new ReviewShard(shard.shardId(), shard.kind(), shard.moduleKey(), left, null, shard.contextPaths()));
            }
        }
        return out;
    }

    public List<ReviewShard> unbundled(List<ReviewShard> shards) {
        if (shards == null) {
            return List.of();
        }
        // 契约包、调用链簇已在同上下文评过，不再进路径启发式交叉评审
        return shards.stream().filter(s -> {
            String id = s.shardId();
            return id == null || (!id.startsWith("bundle-") && !id.startsWith("chain-"));
        }).toList();
    }

    private List<List<DiffFile>> groups(List<DiffFile> files) {
        Map<String, List<DiffFile>> byStem = new LinkedHashMap<>();
        for (DiffFile f : files) {
            String stem = stem(f.path());
            if (stem.isBlank()) {
                continue;
            }
            byStem.computeIfAbsent(stem, k -> new ArrayList<>()).add(f);
        }
        List<List<DiffFile>> groups = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (List<DiffFile> group : byStem.values()) {
            if (group.size() < 2 || !contractPair(group)) {
                continue;
            }
            groups.add(group);
            for (DiffFile f : group) {
                used.add(norm(f.path()));
            }
        }
        List<DiffFile> front = new ArrayList<>();
        List<DiffFile> back = new ArrayList<>();
        for (DiffFile f : files) {
            if (used.contains(norm(f.path()))) {
                continue;
            }
            String p = norm(f.path());
            if (p.endsWith(".ts") || p.endsWith(".tsx") || p.endsWith(".js") || p.endsWith(".vue")) {
                front.add(f);
            } else if (p.endsWith(".java")) {
                back.add(f);
            }
        }
        Set<String> backPaths = mappings(back);
        if (!backPaths.isEmpty()) {
            List<DiffFile> hitFront = new ArrayList<>();
            for (DiffFile f : front) {
                if (mentions(f.patch(), backPaths)) {
                    hitFront.add(f);
                }
            }
            if (!hitFront.isEmpty()) {
                List<DiffFile> hitBack = new ArrayList<>();
                for (DiffFile f : back) {
                    if (mentions(f.patch(), backPaths)) {
                        hitBack.add(f);
                    }
                }
                List<DiffFile> mix = new ArrayList<>(hitBack);
                mix.addAll(hitFront);
                if (mix.size() >= 2) {
                    groups.add(mix);
                }
            }
        }
        return groups;
    }

    private static boolean contractPair(List<DiffFile> group) {
        boolean javaApi = false;
        boolean dto = false;
        boolean xml = false;
        boolean mapper = false;
        for (DiffFile f : group) {
            String name = fileName(f.path());
            if (name.endsWith("controller.java") || name.endsWith("facade.java") || name.endsWith("api.java")) {
                javaApi = true;
            }
            if (name.endsWith("dto.java") || name.endsWith("vo.java")
                    || name.endsWith("request.java") || name.endsWith("response.java")) {
                dto = true;
            }
            if (name.endsWith("mapper.java")) {
                mapper = true;
            }
            if (name.endsWith(".xml")) {
                xml = true;
            }
        }
        return (javaApi && dto) || (mapper && xml);
    }

    private static Set<String> mappings(List<DiffFile> javaFiles) {
        Set<String> paths = new LinkedHashSet<>();
        for (DiffFile f : javaFiles) {
            Matcher m = MAPPING.matcher(f.patch() == null ? "" : f.patch());
            while (m.find()) {
                paths.add(m.group(1));
            }
        }
        return paths;
    }

    private static boolean mentions(String patch, Set<String> paths) {
        if (patch == null) {
            return false;
        }
        Matcher m = URL.matcher(patch);
        while (m.find()) {
            String url = m.group(1);
            for (String p : paths) {
                if (url.contains(p) || p.contains(url)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ShardKind dominant(List<DiffFile> group) {
        for (DiffFile f : group) {
            String name = fileName(f.path());
            if (name.endsWith(".java")) {
                return ShardKind.BACKEND;
            }
        }
        for (DiffFile f : group) {
            if (fileName(f.path()).endsWith(".xml")) {
                return ShardKind.MYBATIS_XML;
            }
        }
        return ShardKind.FRONTEND;
    }

    static String stem(String path) {
        String name = fileName(path);
        name = name.replaceAll("\\.(java|xml|ts|tsx|js|vue)$", "");
        name = name.replaceAll("(?i)(controller|service|mapper|dto|vo|request|response|facade|api)$", "");
        return name.toLowerCase(Locale.ROOT);
    }

    private static String fileName(String path) {
        String p = norm(path);
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String norm(String path) {
        return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
