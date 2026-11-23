package com.lonple.dap.server.modules.review.partition;

import com.lonple.dap.server.modules.review.store.ReviewProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大小分流 + 类型分片 + 路径模块再切 + 批切（全覆盖、不 silent 丢文件）。
 */
@Component
public class DiffPartitioner {
    public enum Mode {
        SINGLE,
        SHARDED
    }

    public record PartitionPlan(Mode mode, List<ReviewShard> shards, List<DiffFile> skippedOther) {
    }

    private final FileTypeClassifier classifier;
    private final PathModuleSplitter moduleSplitter;
    private final ReviewProperties properties;

    public DiffPartitioner(FileTypeClassifier classifier,
                           PathModuleSplitter moduleSplitter,
                           ReviewProperties properties) {
        this.classifier = classifier;
        this.moduleSplitter = moduleSplitter;
        this.properties = properties;
    }

    public PartitionPlan partition(List<DiffFile> allFiles) {
        if (allFiles == null || allFiles.isEmpty()) {
            return new PartitionPlan(Mode.SINGLE, List.of(), List.of());
        }

        Map<ShardKind, List<DiffFile>> byKind = new EnumMap<>(ShardKind.class);
        for (DiffFile f : allFiles) {
            ShardKind kind = classifier.classify(f.path());
            byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(f);
        }

        List<DiffFile> skipped = List.copyOf(byKind.getOrDefault(ShardKind.OTHER, List.of()));
        int reviewKinds = 0;
        for (ShardKind kind : ShardKind.values()) {
            if (kind == ShardKind.OTHER) {
                continue;
            }
            List<DiffFile> files = byKind.get(kind);
            if (files != null && !files.isEmpty()) {
                reviewKinds++;
            }
        }

        int totalFiles = allFiles.size();
        int totalChars = allFiles.stream().mapToInt(DiffFile::charCount).sum();
        int singleMaxFiles = properties.getDiff().getSingleMaxFiles();
        int singleMaxChars = properties.getDiff().getSingleMaxChars();

        // 多类型（如 FE+BE）禁止塞进 SINGLE：否则只选一个「主类型」规则，另一侧等于没按规范评。
        boolean sizeAllowsSingle = totalFiles <= singleMaxFiles && totalChars <= singleMaxChars;
        if (sizeAllowsSingle && reviewKinds <= 1) {
            return new PartitionPlan(Mode.SINGLE, List.of(buildSingleShard(allFiles)), skipped);
        }

        List<ReviewShard> shards = new ArrayList<>();
        for (ShardKind kind : ShardKind.values()) {
            if (kind == ShardKind.OTHER) {
                continue;
            }
            List<DiffFile> files = byKind.get(kind);
            if (files == null || files.isEmpty()) {
                continue;
            }
            shards.addAll(splitKind(kind, files));
        }

        return new PartitionPlan(Mode.SHARDED, normalizeBatchIds(shards), skipped);
    }

    private ReviewShard buildSingleShard(List<DiffFile> files) {
        Map<ShardKind, Integer> counts = new EnumMap<>(ShardKind.class);
        for (DiffFile f : files) {
            ShardKind k = classifier.classify(f.path());
            if (k == ShardKind.OTHER) {
                continue;
            }
            counts.merge(k, 1, Integer::sum);
        }
        ShardKind primary = ShardKind.BACKEND;
        int best = -1;
        for (Map.Entry<ShardKind, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                primary = e.getKey();
            }
        }
        return new ReviewShard(
                "single-" + primary.name().toLowerCase(),
                primary,
                "_all",
                files,
                ReviewShard.joinPatches(files));
    }

    private List<ReviewShard> splitKind(ShardKind kind, List<DiffFile> files) {
        int shardMaxFiles = properties.getDiff().getShardMaxFiles();
        int shardMaxChars = properties.getDiff().getShardMaxChars();
        int chars = files.stream().mapToInt(DiffFile::charCount).sum();
        boolean needModule = files.size() > shardMaxFiles || chars > shardMaxChars;

        if (!needModule) {
            String id = kind.name().toLowerCase() + "-_all";
            return List.of(new ReviewShard(id, kind, "_all", files, ReviewShard.joinPatches(files)));
        }

        List<ReviewShard> out = new ArrayList<>();
        Map<String, List<DiffFile>> byModule = moduleSplitter.groupByModule(files);
        for (Map.Entry<String, List<DiffFile>> e : byModule.entrySet()) {
            out.addAll(batchModule(kind, e.getKey(), e.getValue(), shardMaxFiles, shardMaxChars));
        }
        return out;
    }

    private List<ReviewShard> batchModule(ShardKind kind,
                                          String moduleKey,
                                          List<DiffFile> files,
                                          int maxFiles,
                                          int maxChars) {
        List<List<DiffFile>> batches = new ArrayList<>();
        List<DiffFile> current = new ArrayList<>();
        int currentChars = 0;

        for (DiffFile f : files) {
            boolean overflow = !current.isEmpty()
                    && (current.size() + 1 > maxFiles || currentChars + f.charCount() > maxChars);
            if (overflow) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(f);
            currentChars += f.charCount();
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }

        List<ReviewShard> out = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<DiffFile> batch = batches.get(i);
            String id = batches.size() == 1
                    ? kind.name().toLowerCase() + "-" + moduleKey
                    : kind.name().toLowerCase() + "-" + moduleKey + "#batch" + (i + 1);
            out.add(new ReviewShard(id, kind, moduleKey, batch, ReviewShard.joinPatches(batch)));
        }
        return out;
    }

    /** 同一 kind+module 多片时，统一加上 #batchN。 */
    static List<ReviewShard> normalizeBatchIds(List<ReviewShard> shards) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReviewShard s : shards) {
            counts.merge(s.kind() + "|" + s.moduleKey(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<ReviewShard> out = new ArrayList<>();
        for (ReviewShard s : shards) {
            String key = s.kind() + "|" + s.moduleKey();
            int total = counts.getOrDefault(key, 1);
            if (total <= 1 || s.shardId().contains("#batch")) {
                out.add(s);
                continue;
            }
            int n = seen.merge(key, 1, Integer::sum);
            String id = s.kind().name().toLowerCase() + "-" + s.moduleKey() + "#batch" + n;
            out.add(new ReviewShard(id, s.kind(), s.moduleKey(), s.files(), s.diffText()));
        }
        return out;
    }
}
