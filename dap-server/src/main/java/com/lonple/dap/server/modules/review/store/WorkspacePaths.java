package com.lonple.dap.server.modules.review.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 解析相对仓库根的路径。兼容 IDE 以仓库根为 cwd，以及 bootRun 以 ada-review/ 为 cwd。
 */
public final class WorkspacePaths {
    private WorkspacePaths() {
    }

    public static Path cwd() {
        return Path.of("").toAbsolutePath().normalize();
    }

    /** 解析已存在的规则等文件；都不存在时返回优先候选，供错误信息使用。 */
    public static Path resolveExistingFile(String configured) {
        Path raw = Path.of(configured);
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Set<Path> candidates = candidates(configured);
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return candidates.iterator().next();
    }

    /** 解析数据目录：已存在则用之，否则在检测到的仓库根下创建。 */
    public static Path resolveDataDir(String configured) {
        Path raw = Path.of(configured);
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Set<Path> candidates = candidates(configured);
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return detectRepoRoot().resolve(stripDotDot(configured)).normalize();
    }

    private static Set<Path> candidates(String configured) {
        Path cwd = cwd();
        String fromRepo = stripDotDot(configured);
        Set<Path> out = new LinkedHashSet<>();
        out.add(cwd.resolve(configured).normalize());
        out.add(cwd.resolve(fromRepo).normalize());
        Path parent = cwd.getParent();
        if (parent != null) {
            out.add(parent.resolve(configured).normalize());
            out.add(parent.resolve(fromRepo).normalize());
        }
        return out;
    }

    /** {@code ../rule/x.md} / {@code ./rule/x.md} → {@code rule/x.md} */
    static String stripDotDot(String configured) {
        String s = configured.replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("../")) {
            s = s.substring(3);
        }
        return s;
    }

    static Path detectRepoRoot() {
        Path cwd = cwd();
        if (looksLikeRepoRoot(cwd)) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && looksLikeRepoRoot(parent)) {
            return parent;
        }
        if (parent != null && "ada-review".equals(String.valueOf(cwd.getFileName()))) {
            return parent;
        }
        return cwd;
    }

    private static boolean looksLikeRepoRoot(Path dir) {
        return Files.isDirectory(dir.resolve("ada-review"))
                || Files.isRegularFile(dir.resolve("rule").resolve("java_review.md"));
    }
}
