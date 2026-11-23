package com.lonple.dap.codetools.service;

import com.lonple.dap.codetools.api.Dto;
import com.lonple.dap.codetools.config.SandboxProps;
import com.lonple.dap.codetools.fs.WorkspaceGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class CodeWorkspaceService {

    private static final int MAX_READ_CHARS = 100_000;
    private static final int MAX_LIST = 500;
    private static final int OUTPUT_CHARS = 20_000;
    private static final int DEFAULT_TIMEOUT_SEC = 180;
    private static final int START_TIMEOUT_SEC = 8;

    private static final Set<String> ACTIONS = Set.of(
            "tool_version",
            "git_status", "git_diff", "git_log",
            "build_gradle", "test_gradle", "start_gradle",
            "build_maven", "test_maven", "start_maven", "mvn_validate", "mvn_deps_tree", "gradle_deps",
            "build_vite", "test_node", "start_vite",
            "test_uv", "start_uv",
            "build", "test", "start_dev",
            "stop_dev", "list_processes");

    private final WorkspaceGuard guard;
    private final SandboxProps props;
    private final Map<Long, Process> background = new ConcurrentHashMap<>();
    private final AtomicLong bgSeq = new AtomicLong();

    public Dto.ListResult listDir(String relativePath) {
        Path root = guard.root();
        Path dir = guard.resolve(relativePath, true);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("不是目录：" + relativePath);
        }
        Dto.ListResult result = new Dto.ListResult();
        result.setRepoPath(root.toString());
        result.setRelativePath(toRel(root, dir));
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (count++ >= MAX_LIST) {
                    break;
                }
                Dto.ListResult.Entry e = new Dto.ListResult.Entry();
                e.setName(p.getFileName().toString());
                e.setRelativePath(toRel(root, p));
                e.setDirectory(Files.isDirectory(p));
                if (Files.isRegularFile(p)) {
                    e.setSize(Files.size(p));
                }
                result.getEntries().add(e);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("list failed: " + e.getMessage());
        }
        return result;
    }

    public Dto.ReadResult readFile(String relativePath, Integer startLine, Integer endLine, Integer maxChars) {
        Path root = guard.root();
        Path file = guard.resolve(relativePath, true);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("不是文件：" + relativePath);
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = startLine == null || startLine < 1 ? 1 : startLine;
            int to = endLine == null || endLine < from ? lines.size() : Math.min(endLine, lines.size());
            int limit = maxChars == null || maxChars <= 0 ? MAX_READ_CHARS : Math.min(maxChars, MAX_READ_CHARS);
            StringBuilder sb = new StringBuilder();
            boolean truncated = false;
            for (int i = from; i <= to; i++) {
                String line = lines.get(i - 1);
                if (sb.length() + line.length() + 1 > limit) {
                    truncated = true;
                    break;
                }
                sb.append(line).append('\n');
            }
            Dto.ReadResult r = new Dto.ReadResult();
            r.setRepoPath(root.toString());
            r.setRelativePath(toRel(root, file));
            r.setContent(sb.toString());
            r.setStartLine(from);
            r.setEndLine(Math.min(to, from + Math.max(0, sb.toString().split("\n", -1).length - 1)));
            r.setTruncated(truncated);
            return r;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("read failed: " + e.getMessage());
        }
    }

    public Dto.GenericResult writeFile(String relativePath, String content) {
        Path root = guard.root();
        Path file = guard.resolve(relativePath, false);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Path real = file.toRealPath();
            if (!real.startsWith(root)) {
                Files.deleteIfExists(real);
                throw new IllegalArgumentException("写入后路径越界");
            }
            Dto.GenericResult r = new Dto.GenericResult();
            r.setMessage("ok");
            r.getData().put("relativePath", toRel(root, real));
            return r;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("write failed: " + e.getMessage());
        }
    }

    public Dto.GenericResult deleteFile(String relativePath) {
        Path root = guard.root();
        Path target = guard.resolve(relativePath, true);
        try {
            if (Files.isDirectory(target)) {
                throw new IllegalArgumentException("不支持删除目录：" + relativePath);
            }
            Files.delete(target);
            Dto.GenericResult r = new Dto.GenericResult();
            r.setMessage("ok");
            r.getData().put("relativePath", toRel(root, target));
            return r;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("delete failed: " + e.getMessage());
        }
    }

    public Map<String, Object> search(String query) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        Path root = guard.root();
        List<Map<String, Object>> hits = searchWithRipgrep(root, query);
        if (hits != null) {
            return Map.of("query", query, "hits", hits, "engine", "ripgrep");
        }
        hits = searchWithWalk(root, query);
        return Map.of("query", query, "hits", hits, "engine", "java-walk");
    }

    public Dto.ActionResult runAction(String action, Map<String, String> args) {
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(act)) {
            throw new UnsupportedOperationException("不支持的动作（不在白名单）：" + act);
        }
        if ("stop_dev".equals(act)) {
            int n = 0;
            for (Process p : background.values()) {
                if (p.isAlive()) {
                    p.destroyForcibly();
                    n++;
                }
            }
            background.clear();
            Dto.ActionResult r = new Dto.ActionResult();
            r.setAction(act);
            r.setExitCode(0);
            r.setStdout("stopped=" + n);
            return r;
        }
        if ("list_processes".equals(act)) {
            Dto.ActionResult r = new Dto.ActionResult();
            r.setAction(act);
            r.setExitCode(0);
            r.setStdout("background=" + background.size());
            return r;
        }
        Path root = guard.root();
        Map<String, String> a = args == null ? Map.of() : args;
        if (StringUtils.hasText(a.get("workdir"))) {
            root = guard.resolve(a.get("workdir"), true);
        }
        List<String> cmd = buildCommand(root, act, a);
        boolean backgroundStart = act.startsWith("start_") || "start_dev".equals(act);
        return exec(root, act, cmd, backgroundStart ? START_TIMEOUT_SEC : props.getActionTimeoutSec(), backgroundStart);
    }

    public Dto.GenericResult commit(String message, String authorName, String authorEmail) {
        Path root = guard.root();
        exec(root, "git_add", List.of("git", "add", "-A"), DEFAULT_TIMEOUT_SEC, false);
        Dto.ActionResult c = exec(root, "git_commit", List.of(
                "git",
                "-c", "user.name=" + (authorName == null ? "dap-sandbox" : authorName),
                "-c", "user.email=" + (authorEmail == null ? "sandbox@dap.local" : authorEmail),
                "commit", "-m", message == null ? "dap sandbox commit" : message),
                DEFAULT_TIMEOUT_SEC, false);
        Dto.GenericResult r = new Dto.GenericResult();
        r.setOk(c.getExitCode() == 0);
        r.setMessage(c.getExitCode() == 0 ? "committed" : trim(c.getStderr() + c.getStdout(), 2000));
        r.getData().put("exitCode", c.getExitCode());
        return r;
    }

    private List<String> buildCommand(Path root, String action, Map<String, String> args) {
        return switch (action) {
            case "tool_version" -> versionCommand(args.getOrDefault("tool", "java"));
            case "git_status" -> List.of("git", "status", "--short");
            case "git_diff" -> {
                String path = args.get("path");
                if (StringUtils.hasText(path)) {
                    guard.resolve(path, false);
                    yield List.of("git", "diff", "--stat", "--", path.replace('\\', '/'));
                }
                yield List.of("git", "diff", "--stat");
            }
            case "git_log" -> List.of("git", "log", "-n", "20", "--oneline");
            case "build_gradle" -> gradle(root, List.of("build", "-x", "test"));
            case "test_gradle" -> gradle(root, List.of("test"));
            case "start_gradle" -> gradle(root, List.of("bootRun"));
            case "build_maven" -> List.of("mvn", "-q", "-DskipTests", "package");
            case "test_maven" -> List.of("mvn", "-q", "test");
            case "start_maven" -> List.of("mvn", "-q", "spring-boot:run");
            case "mvn_validate" -> List.of("mvn", "-q", "validate");
            case "mvn_deps_tree" -> List.of("mvn", "-q", "dependency:tree");
            case "gradle_deps" -> gradle(root, List.of("dependencies"));
            case "build_vite" -> npmOrPnpm(root, List.of("run", "build"));
            case "test_node" -> npmOrPnpm(root, List.of("test"));
            case "start_vite" -> npmOrPnpm(root, List.of("run", "dev"));
            case "test_uv" -> {
                String path = args.get("path");
                if (StringUtils.hasText(path)) {
                    guard.resolve(path, false);
                    yield List.of("uv", "run", "pytest", "-q", path.replace('\\', '/'));
                }
                yield List.of("uv", "run", "pytest", "-q");
            }
            case "start_uv" -> {
                String entry = args.getOrDefault("entry", "dap-agent");
                if (!entry.matches("^[a-zA-Z0-9._-]{1,64}$")) {
                    throw new IllegalArgumentException("uv 入口名非法");
                }
                yield List.of("uv", "run", entry);
            }
            case "build", "test", "start_dev" -> autoDetect(root, action);
            default -> throw new UnsupportedOperationException(action);
        };
    }

    private List<String> autoDetect(Path root, String kind) {
        boolean hasGradlew = Files.exists(root.resolve("gradlew"))
                || Files.exists(root.resolve("build.gradle"))
                || Files.exists(root.resolve("build.gradle.kts"));
        boolean hasPom = Files.exists(root.resolve("pom.xml"));
        boolean hasPkg = Files.exists(root.resolve("package.json"));
        boolean hasPy = Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("pytest.ini"));
        return switch (kind) {
            case "build" -> {
                if (hasGradlew) {
                    yield gradle(root, List.of("build", "-x", "test"));
                }
                if (hasPom) {
                    yield List.of("mvn", "-q", "-DskipTests", "package");
                }
                if (hasPkg) {
                    yield npmOrPnpm(root, List.of("run", "build"));
                }
                throw new IllegalArgumentException("无法自动识别构建系统");
            }
            case "test" -> {
                if (hasGradlew) {
                    yield gradle(root, List.of("test"));
                }
                if (hasPom) {
                    yield List.of("mvn", "-q", "test");
                }
                if (hasPkg) {
                    yield npmOrPnpm(root, List.of("test"));
                }
                if (hasPy) {
                    yield List.of("uv", "run", "pytest", "-q");
                }
                throw new IllegalArgumentException("无法自动识别测试命令");
            }
            case "start_dev" -> {
                if (hasGradlew) {
                    yield gradle(root, List.of("bootRun"));
                }
                if (hasPom) {
                    yield List.of("mvn", "-q", "spring-boot:run");
                }
                if (hasPkg) {
                    yield npmOrPnpm(root, List.of("run", "dev"));
                }
                if (hasPy) {
                    yield List.of("uv", "run", "dap-agent");
                }
                throw new IllegalArgumentException("无法自动识别启动命令");
            }
            default -> throw new IllegalArgumentException("未知动作");
        };
    }

    private List<String> versionCommand(String tool) {
        String t = tool.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "java" -> List.of("java", "-version");
            case "javac" -> List.of("javac", "-version");
            case "mvn", "maven" -> List.of("mvn", "-v");
            case "gradle" -> List.of("gradle", "-v");
            case "node" -> List.of("node", "-v");
            case "npm" -> List.of("npm", "-v");
            case "pnpm" -> List.of("pnpm", "-v");
            case "uv" -> List.of("uv", "--version");
            case "python" -> List.of("python3", "--version");
            case "git" -> List.of("git", "--version");
            case "rg", "ripgrep" -> List.of("rg", "--version");
            default -> throw new IllegalArgumentException("tool_version 不支持: " + tool);
        };
    }

    private List<String> gradle(Path root, List<String> tasks) {
        Path gw = root.resolve("gradlew");
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(gw) || Files.exists(gw)) {
            cmd.add(gw.toAbsolutePath().toString());
        } else {
            cmd.add("gradle");
        }
        cmd.addAll(tasks);
        return cmd;
    }

    private List<String> npmOrPnpm(Path root, List<String> args) {
        boolean pnpm = Files.exists(root.resolve("pnpm-lock.yaml"));
        String exe = pnpm ? "pnpm" : "npm";
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(args);
        if (args.size() >= 1) {
            String a0 = args.get(0);
            if ("run".equals(a0)) {
                if (args.size() < 2 || !List.of("build", "dev", "test").contains(args.get(1))) {
                    throw new IllegalArgumentException("npm/pnpm 仅允许 run build|dev|test");
                }
            } else if (!"test".equals(a0)) {
                throw new IllegalArgumentException("npm/pnpm 参数不在白名单");
            }
        }
        return cmd;
    }

    private Dto.ActionResult exec(Path root, String action, List<String> cmd, int timeoutSec, boolean backgroundStart) {
        Dto.ActionResult r = new Dto.ActionResult();
        r.setAction(action);
        r.setRepoPath(root.toString());
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            if (backgroundStart) {
                long id = bgSeq.incrementAndGet();
                background.put(id, p);
                Thread tOut = drain(p.getInputStream(), stdout);
                Thread tErr = drain(p.getErrorStream(), stderr);
                p.waitFor(timeoutSec, TimeUnit.SECONDS);
                try {
                    tOut.join(300);
                    tErr.join(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                r.setBackground(true);
                r.setPid(p.pid());
                r.setExitCode(p.isAlive() ? -1 : p.exitValue());
                r.setStdout(trim(stdout.toString(), OUTPUT_CHARS));
                r.setStderr(trim(stderr.toString(), OUTPUT_CHARS));
                return r;
            }
            Thread tOut = drain(p.getInputStream(), stdout);
            Thread tErr = drain(p.getErrorStream(), stderr);
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                r.setTimedOut(true);
                r.setExitCode(-1);
                r.setStdout(trim(stdout.toString(), OUTPUT_CHARS));
                r.setStderr(trim(stderr.toString(), OUTPUT_CHARS) + "\n(timeout)");
                return r;
            }
            tOut.join(1000);
            tErr.join(1000);
            r.setExitCode(p.exitValue());
            r.setStdout(trim(stdout.toString(), OUTPUT_CHARS));
            r.setStderr(trim(stderr.toString(), OUTPUT_CHARS));
            return r;
        } catch (Exception e) {
            r.setExitCode(-1);
            r.setStderr(e.getMessage());
            return r;
        }
    }

    private List<Map<String, Object>> searchWithRipgrep(Path root, String query) {
        String exe = props.getRipgrepExecutable();
        if (!StringUtils.hasText(exe)) {
            return null;
        }
        List<String> cmd = List.of(
                exe, "--line-number", "--with-filename", "--no-heading", "--color", "never",
                "--max-count", "5", "-F", query, ".");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(props.getRipgrepTimeoutSec(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() > 1) {
                return null;
            }
            List<Map<String, Object>> hits = new ArrayList<>();
            int max = Math.max(1, props.getRipgrepMaxHits());
            for (String line : out.toString().split("\n")) {
                if (hits.size() >= max || line.isBlank()) {
                    continue;
                }
                int c1 = line.indexOf(':');
                int c2 = c1 < 0 ? -1 : line.indexOf(':', c1 + 1);
                if (c1 < 0 || c2 < 0) {
                    continue;
                }
                String path = line.substring(0, c1).replace('\\', '/');
                if (path.startsWith("./")) {
                    path = path.substring(2);
                }
                String lineNo = line.substring(c1 + 1, c2);
                String text = line.substring(c2 + 1);
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("relativePath", path);
                try {
                    hit.put("line", Integer.parseInt(lineNo.trim()));
                } catch (NumberFormatException e) {
                    hit.put("line", lineNo);
                }
                hit.put("text", trim(text, 200));
                hits.add(hit);
            }
            return hits;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> searchWithWalk(Path root, String query) {
        List<Map<String, Object>> hits = new ArrayList<>();
        int max = Math.max(1, props.getRipgrepMaxHits());
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return !(n.endsWith(".class") || n.endsWith(".jar") || n.endsWith(".png") || n.endsWith(".jpg"));
                    })
                    .limit(2000)
                    .forEach(p -> {
                        if (hits.size() >= max) {
                            return;
                        }
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (int i = 0; i < lines.size() && hits.size() < max; i++) {
                                if (lines.get(i).contains(query)) {
                                    Map<String, Object> hit = new LinkedHashMap<>();
                                    hit.put("relativePath", toRel(root, p));
                                    hit.put("line", i + 1);
                                    hit.put("text", trim(lines.get(i), 200));
                                    hits.add(hit);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            throw new IllegalArgumentException("search failed: " + e.getMessage());
        }
        return hits;
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String toRel(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n...[truncated]";
    }
}
