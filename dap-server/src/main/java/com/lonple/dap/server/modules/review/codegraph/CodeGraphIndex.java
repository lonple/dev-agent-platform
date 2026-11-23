package com.lonple.dap.server.modules.review.codegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 符号调用图。symbol 形如 OrderService#create。
 */
public class CodeGraphIndex {
    private final Map<String, String> symbolFile = new LinkedHashMap<>();
    private final Map<String, Set<String>> fileSymbols = new LinkedHashMap<>();
    private final Map<String, Set<String>> callees = new LinkedHashMap<>();
    private final Map<String, Set<String>> callers = new LinkedHashMap<>();

    public void addSymbol(String symbol, String file) {
        if (symbol == null || symbol.isBlank() || file == null || file.isBlank()) {
            return;
        }
        symbolFile.put(symbol, file.replace('\\', '/'));
        fileSymbols.computeIfAbsent(file.replace('\\', '/'), k -> new LinkedHashSet<>()).add(symbol);
    }

    public void addCall(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        callees.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        callers.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
    }

    public int symbolCount() {
        return symbolFile.size();
    }

    public List<String> relatedFiles(Set<String> seedFiles, int depth, int maxFiles) {
        Set<String> seeds = normalize(seedFiles);
        Set<String> seen = walkSymbols(seeds, depth);
        List<String> files = new ArrayList<>();
        for (String symbol : seen) {
            String file = symbolFile.get(symbol);
            if (file == null || seeds.contains(file) || files.contains(file)) {
                continue;
            }
            files.add(file);
            if (files.size() >= maxFiles) {
                break;
            }
        }
        return files;
    }

    /**
     * 在「已改文件」集合内做连通分量：两文件若在调用图上深度 {@code depth} 内可达（可经未改中间文件），则归为一簇。
     * 不在图中的路径各自成单点簇。
     */
    public List<Set<String>> clusterChangedFiles(Set<String> changedFiles, int depth) {
        Set<String> changed = normalize(changedFiles);
        Map<String, String> parent = new LinkedHashMap<>();
        for (String f : changed) {
            parent.put(f, f);
        }
        for (String file : changed) {
            if (fileSymbols.getOrDefault(file, Set.of()).isEmpty()) {
                continue;
            }
            Set<String> reached = walkSymbols(Set.of(file), depth);
            for (String symbol : reached) {
                String other = symbolFile.get(symbol);
                if (other == null || other.equals(file) || !changed.contains(other)) {
                    continue;
                }
                union(parent, file, other);
            }
        }
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (String f : changed) {
            String root = find(parent, f);
            groups.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(f);
        }
        return new ArrayList<>(groups.values());
    }

    private Set<String> walkSymbols(Set<String> seedFiles, int depth) {
        Set<String> start = new LinkedHashSet<>();
        for (String file : seedFiles) {
            start.addAll(fileSymbols.getOrDefault(file, Set.of()));
        }
        Set<String> seen = new LinkedHashSet<>(start);
        ArrayDeque<String> q = new ArrayDeque<>(start);
        Map<String, Integer> depthOf = new LinkedHashMap<>();
        for (String s : start) {
            depthOf.put(s, 0);
        }
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            int d = depthOf.getOrDefault(cur, 0);
            if (d >= depth) {
                continue;
            }
            Set<String> next = new LinkedHashSet<>();
            next.addAll(callees.getOrDefault(cur, Set.of()));
            next.addAll(callers.getOrDefault(cur, Set.of()));
            for (String n : next) {
                if (seen.add(n)) {
                    depthOf.put(n, d + 1);
                    q.addLast(n);
                }
            }
        }
        return seen;
    }

    private static Set<String> normalize(Set<String> files) {
        Set<String> out = new LinkedHashSet<>();
        if (files == null) {
            return out;
        }
        for (String f : files) {
            if (f != null && !f.isBlank()) {
                out.add(f.replace('\\', '/'));
            }
        }
        return out;
    }

    private static String find(Map<String, String> parent, String x) {
        String p = parent.get(x);
        if (p == null) {
            parent.put(x, x);
            return x;
        }
        if (!p.equals(x)) {
            p = find(parent, p);
            parent.put(x, p);
        }
        return p;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    public Impact impact(String symbol, int depth, int maxNodes) {
        String key = resolve(symbol);
        if (key == null) {
            return new Impact(symbol, List.of(), List.of(), List.of(), false);
        }
        List<String> up = walk(key, callers, depth, maxNodes);
        List<String> down = walk(key, callees, depth, maxNodes);
        Set<String> files = new LinkedHashSet<>();
        String self = symbolFile.get(key);
        if (self != null) {
            files.add(self);
        }
        for (String s : up) {
            String f = symbolFile.get(s);
            if (f != null) {
                files.add(f);
            }
        }
        for (String s : down) {
            String f = symbolFile.get(s);
            if (f != null) {
                files.add(f);
            }
        }
        boolean truncated = up.size() >= maxNodes || down.size() >= maxNodes;
        return new Impact(key, up, down, new ArrayList<>(files), truncated);
    }

    private String resolve(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        if (symbolFile.containsKey(symbol)) {
            return symbol;
        }
        String simple = symbol.contains("#") ? symbol.substring(symbol.indexOf('#') + 1) : symbol;
        String found = null;
        for (String key : symbolFile.keySet()) {
            if (key.endsWith("#" + simple) || key.equals(simple)) {
                if (found != null) {
                    return found;
                }
                found = key;
            }
        }
        return found;
    }

    private static List<String> walk(String start, Map<String, Set<String>> edges, int depth, int maxNodes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        Map<String, Integer> depthOf = new LinkedHashMap<>();
        q.add(start);
        depthOf.put(start, 0);
        seen.add(start);
        while (!q.isEmpty() && out.size() < maxNodes) {
            String cur = q.removeFirst();
            int d = depthOf.getOrDefault(cur, 0);
            if (d >= depth) {
                continue;
            }
            for (String n : edges.getOrDefault(cur, Set.of())) {
                if (!seen.add(n)) {
                    continue;
                }
                depthOf.put(n, d + 1);
                out.add(n);
                q.add(n);
                if (out.size() >= maxNodes) {
                    break;
                }
            }
        }
        return out;
    }

    public record Impact(String symbol, List<String> callers, List<String> callees, List<String> files, boolean truncated) {
    }
}
