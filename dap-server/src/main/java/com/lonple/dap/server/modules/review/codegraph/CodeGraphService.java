package com.lonple.dap.server.modules.review.codegraph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.lonple.dap.server.modules.review.partition.ReviewShard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class CodeGraphService {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphService.class);
    private static final Pattern NS = Pattern.compile("<mapper\\s+namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("<(select|insert|update|delete)\\s+[^>]*id\\s*=\\s*\"([^\"]+)\"");
    private static final int DEPTH = 4;
    private static final int MAX_FILES = 30;
    /** 单次评审任务内复用，任务结束由 release 清掉。 */
    private final ConcurrentHashMap<String, CodeGraphIndex> taskIndex = new ConcurrentHashMap<>();

    /**
     * 同一 taskId 只扫描一次工作区。
     */
    public CodeGraphIndex indexForTask(String taskId, Path workTree) {
        CodeGraphIndex existing = taskIndex.get(taskId);
        if (existing != null) {
            log.info("codegraph reuse taskId={} symbols={}", taskId, existing.symbolCount());
            return existing;
        }
        CodeGraphIndex built = index(workTree);
        CodeGraphIndex raced = taskIndex.putIfAbsent(taskId, built);
        if (raced != null) {
            log.info("codegraph reuse taskId={} symbols={}", taskId, raced.symbolCount());
            return raced;
        }
        return built;
    }

    public void release(String taskId) {
        if (taskId == null) {
            return;
        }
        if (taskIndex.remove(taskId) != null) {
            log.info("codegraph released taskId={}", taskId);
        }
    }

    public CodeGraphIndex index(Path workTree) {
        CodeGraphIndex index = new CodeGraphIndex();
        if (workTree == null || !Files.isDirectory(workTree)) {
            return index;
        }
        try (Stream<Path> walk = Files.walk(workTree, 16)) {
            walk.filter(Files::isRegularFile).forEach(p -> ingest(workTree, p, index));
        } catch (IOException e) {
            log.warn("codegraph walk failed: {}", e.getMessage());
        }
        log.info("codegraph indexed symbols={}", index.symbolCount());
        return index;
    }

    public List<ReviewShard> attach(List<ReviewShard> shards, CodeGraphIndex index) {
        if (shards == null || shards.isEmpty() || index == null || index.symbolCount() == 0) {
            return shards == null ? List.of() : shards;
        }
        List<ReviewShard> out = new ArrayList<>();
        for (ReviewShard shard : shards) {
            Set<String> seeds = new LinkedHashSet<>(shard.paths());
            List<String> related = index.relatedFiles(seeds, DEPTH, MAX_FILES);
            out.add(related.isEmpty() ? shard : shard.withContext(related));
        }
        return out;
    }

    public CodeGraphIndex.Impact impact(Path workTree, String symbol) {
        return index(workTree).impact(symbol, DEPTH, 40);
    }

    private void ingest(Path root, Path file, CodeGraphIndex index) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.contains("/build/") || lower.contains("/target/") || lower.contains("/node_modules/")
                || lower.contains("/.git/") || lower.startsWith("build/") || lower.startsWith("target/")) {
            return;
        }
        if (lower.endsWith(".java")) {
            ingestJava(file, rel, index);
        } else if (lower.endsWith(".xml")) {
            ingestXml(file, rel, index);
        }
    }

    private void ingestJava(Path file, String rel, CodeGraphIndex index) {
        try {
            if (Files.size(file) > 200_000) {
                return;
            }
            CompilationUnit cu = StaticJavaParser.parse(file);
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String typeName = type.getNameAsString();
                for (MethodDeclaration method : type.getMethods()) {
                    String symbol = typeName + "#" + method.getNameAsString();
                    index.addSymbol(symbol, rel);
                    method.findAll(MethodCallExpr.class).forEach(call -> {
                        String callee = call.getScope()
                                .map(s -> s.toString().replace("this.", ""))
                                .filter(s -> !s.contains("("))
                                .map(s -> s + "#" + call.getNameAsString())
                                .orElse("#" + call.getNameAsString());
                        if (!callee.startsWith("#")) {
                            index.addSymbol(callee, rel);
                        }
                        index.addCall(symbol, callee);
                    });
                }
            }
        } catch (Exception e) {
            log.debug("skip java {}: {}", rel, e.getMessage());
        }
    }

    private void ingestXml(Path file, String rel, CodeGraphIndex index) {
        try {
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            Matcher ns = NS.matcher(xml);
            if (!ns.find()) {
                return;
            }
            String type = ns.group(1);
            int dot = type.lastIndexOf('.');
            String simple = dot >= 0 ? type.substring(dot + 1) : type;
            Matcher id = ID.matcher(xml);
            while (id.find()) {
                String symbol = simple + "#" + id.group(2);
                index.addSymbol(symbol, rel);
            }
        } catch (IOException ignored) {
            // skip unreadable xml
        }
    }
}
