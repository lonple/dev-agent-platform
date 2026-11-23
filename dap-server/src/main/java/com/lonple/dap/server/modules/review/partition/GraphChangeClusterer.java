package com.lonple.dap.server.modules.review.partition;

import com.lonple.dap.server.modules.review.codegraph.CodeGraphIndex;
import com.lonple.dap.server.modules.review.store.ReviewProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 先按 CodeGraph 把调用相关的「已改文件」聚成评审单元，再按体量切分；
 * 图上连不上的文件交给 {@link DiffPartitioner} 按类型/模块处理。
 */
@Component
public class GraphChangeClusterer {
    static final int CLUSTER_DEPTH = 4;

    private final FileTypeClassifier classifier;
    private final DiffPartitioner partitioner;
    private final ReviewProperties properties;

    public GraphChangeClusterer(FileTypeClassifier classifier,
                                DiffPartitioner partitioner,
                                ReviewProperties properties) {
        this.classifier = classifier;
        this.partitioner = partitioner;
        this.properties = properties;
    }

    /**
     * @param allFiles 完整 diff（含 OTHER）
     * @param index    本任务 CodeGraph
     */
    public DiffPartitioner.PartitionPlan partition(List<DiffFile> allFiles, CodeGraphIndex index) {
        if (allFiles == null || allFiles.isEmpty()) {
            return new DiffPartitioner.PartitionPlan(DiffPartitioner.Mode.SINGLE, List.of(), List.of());
        }

        List<DiffFile> skipped = new ArrayList<>();
        List<DiffFile> reviewable = new ArrayList<>();
        for (DiffFile f : allFiles) {
            if (classifier.classify(f.path()) == ShardKind.OTHER) {
                skipped.add(f);
            } else {
                reviewable.add(f);
            }
        }
        if (reviewable.isEmpty()) {
            return new DiffPartitioner.PartitionPlan(DiffPartitioner.Mode.SINGLE, List.of(), List.copyOf(skipped));
        }

        Map<String, DiffFile> byPath = new LinkedHashMap<>();
        for (DiffFile f : reviewable) {
            byPath.put(norm(f.path()), f);
        }

        List<List<DiffFile>> chains = new ArrayList<>();
        List<DiffFile> leftovers = new ArrayList<>();
        if (index != null && index.symbolCount() > 0) {
            List<Set<String>> clusters = index.clusterChangedFiles(byPath.keySet(), CLUSTER_DEPTH);
            Set<String> placed = new LinkedHashSet<>();
            for (Set<String> cluster : clusters) {
                if (cluster.size() < 2) {
                    continue;
                }
                List<DiffFile> group = new ArrayList<>();
                for (String p : cluster) {
                    DiffFile f = byPath.get(norm(p));
                    if (f != null) {
                        group.add(f);
                        placed.add(norm(f.path()));
                    }
                }
                if (group.size() >= 2) {
                    chains.add(group);
                }
            }
            for (DiffFile f : reviewable) {
                if (!placed.contains(norm(f.path()))) {
                    leftovers.add(f);
                }
            }
        } else {
            leftovers.addAll(reviewable);
        }

        List<ReviewShard> shards = new ArrayList<>();
        int n = 0;
        for (List<DiffFile> chain : chains) {
            n++;
            shards.addAll(splitChain(n, chain));
        }

        DiffPartitioner.PartitionPlan rest = leftovers.isEmpty()
                ? new DiffPartitioner.PartitionPlan(DiffPartitioner.Mode.SINGLE, List.of(), List.of())
                : partitioner.partition(leftovers);
        shards.addAll(rest.shards());

        DiffPartitioner.Mode mode = shards.size() <= 1 && countReviewKinds(shards) <= 1
                ? DiffPartitioner.Mode.SINGLE
                : DiffPartitioner.Mode.SHARDED;
        if (!chains.isEmpty() && shards.size() > 1) {
            mode = DiffPartitioner.Mode.SHARDED;
        }
        return new DiffPartitioner.PartitionPlan(mode, DiffPartitioner.normalizeBatchIds(shards), List.copyOf(skipped));
    }

    public int chainShardCount(List<ReviewShard> shards) {
        if (shards == null) {
            return 0;
        }
        return (int) shards.stream().filter(s -> s.shardId() != null && s.shardId().startsWith("chain-")).count();
    }

    private List<ReviewShard> splitChain(int index, List<DiffFile> files) {
        int maxFiles = properties.getDiff().getShardMaxFiles();
        int maxChars = properties.getDiff().getShardMaxChars();
        ShardKind kind = dominant(files);
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
                    ? "chain-" + index + "-" + kind.name().toLowerCase(Locale.ROOT)
                    : "chain-" + index + "-" + kind.name().toLowerCase(Locale.ROOT) + "#batch" + (i + 1);
            out.add(new ReviewShard(id, kind, "chain", batch, ReviewShard.joinPatches(batch)));
        }
        return out;
    }

    private ShardKind dominant(List<DiffFile> files) {
        Map<ShardKind, Integer> counts = new EnumMap<>(ShardKind.class);
        for (DiffFile f : files) {
            ShardKind k = classifier.classify(f.path());
            if (k != ShardKind.OTHER) {
                counts.merge(k, 1, Integer::sum);
            }
        }
        ShardKind best = ShardKind.BACKEND;
        int n = -1;
        for (Map.Entry<ShardKind, Integer> e : counts.entrySet()) {
            if (e.getValue() > n) {
                n = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static int countReviewKinds(List<ReviewShard> shards) {
        Set<ShardKind> kinds = new LinkedHashSet<>();
        for (ReviewShard s : shards) {
            kinds.add(s.kind());
        }
        return kinds.size();
    }

    private static String norm(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
