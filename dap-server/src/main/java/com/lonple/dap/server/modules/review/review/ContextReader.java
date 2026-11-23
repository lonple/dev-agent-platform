package com.lonple.dap.server.modules.review.review;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.review.store.ReviewProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class ContextReader {
    private final ReviewProperties properties;

    public ContextReader(ReviewProperties properties) {
        this.properties = properties;
    }

    public String readTargets(Path workTree, List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            return "（未指定上下文目标）";
        }
        Path root = workTree.toAbsolutePath().normalize();
        StringBuilder sb = new StringBuilder();
        int total = 0;
        int maxTotal = properties.getContext().getMaxTotalChars();
        int maxFile = properties.getContext().getMaxFileChars();
        for (Target t : targets) {
            if (total >= maxTotal) {
                sb.append("\n... [context total truncated] ...\n");
                break;
            }
            Path file = resolve(root, t);
            sb.append("### ").append(t.type()).append(' ').append(t.name()).append('\n');
            if (file == null) {
                sb.append("（未找到）\n\n");
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.length() > maxFile) {
                    content = content.substring(0, maxFile) + "\n... [file truncated] ...\n";
                }
                if (total + content.length() > maxTotal) {
                    content = content.substring(0, Math.max(0, maxTotal - total)) + "\n... [truncated] ...\n";
                }
                sb.append("path: ").append(root.relativize(file)).append('\n');
                sb.append(content).append("\n\n");
                total += content.length();
            } catch (IOException e) {
                sb.append("（读取失败: ").append(e.getMessage()).append("）\n\n");
            }
        }
        return sb.toString();
    }

    private Path resolve(Path root, Target t) {
        if (StringUtils.hasText(t.pathHint())) {
            Path p = root.resolve(t.pathHint()).normalize();
            ensureInside(root, p);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String name = t.name() == null ? "" : t.name().trim();
        if (name.isEmpty()) {
            return null;
        }
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        String fileName = simple + ".java";
        try (Stream<Path> walk = Files.walk(root, 12)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName)
                            || containsType(p, simple))
                    .filter(p -> {
                        try {
                            ensureInside(root, p);
                            return true;
                        } catch (BizException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean containsType(Path p, String simple) {
        if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
            return false;
        }
        try {
            String head = Files.readString(p, StandardCharsets.UTF_8);
            if (head.length() > 20_000) {
                head = head.substring(0, 20_000);
            }
            return head.contains("class " + simple) || head.contains("interface " + simple)
                    || head.contains("enum " + simple) || head.contains("record " + simple);
        } catch (IOException e) {
            return false;
        }
    }

    private static void ensureInside(Path root, Path target) {
        Path n = target.toAbsolutePath().normalize();
        if (!n.startsWith(root)) {
            throw new BizException("非法路径（禁止逃逸工作区）: " + target);
        }
    }

    public record Target(String type, String name, String pathHint) {
        public static Target of(String type, String name, String pathHint) {
            return new Target(type == null ? "FILE" : type, name, pathHint);
        }
    }
}
