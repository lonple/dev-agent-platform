package com.lonple.dap.server.modules.agenttool.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.config.RipgrepProperties;
import com.lonple.dap.server.modules.agenttool.RipgrepSearchService;
import com.lonple.dap.server.modules.agenttool.dto.CodeRepoDtos;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionCodeRepo;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionCodeRepoMapper;
import com.lonple.dap.server.modules.sandbox.SandboxCodeBridge;
import com.lonple.dap.server.modules.sandbox.SandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.TimeUnit;
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeRepoToolService {

    private static final int MAX_READ_CHARS = 100_000;
    private static final int MAX_LIST = 500;
    private static final int DEFAULT_TIMEOUT_SEC = 180;
    private static final int START_TIMEOUT_SEC = 8;
    private static final int OUTPUT_CHARS = 20_000;

    private final ChatSessionMapper sessionMapper;
    private final SessionCodeRepoMapper codeRepoMapper;
    private final RipgrepSearchService ripgrepSearchService;
    private final RipgrepProperties ripgrepProperties;
    private final CodeRepoProcessRegistry processRegistry;
    private final SandboxProperties sandboxProperties;
    private final SandboxCodeBridge sandboxCodeBridge;

    public WbChatSession requireSession(Long sessionId) {
        WbChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(404, "会话不存在");
        }
        return session;
    }

    public List<WbSessionCodeRepo> listRepos(Long sessionId) {
        return codeRepoMapper.selectList(new LambdaQueryWrapper<WbSessionCodeRepo>()
                .eq(WbSessionCodeRepo::getSessionId, sessionId)
                .orderByAsc(WbSessionCodeRepo::getSortNo)
                .orderByAsc(WbSessionCodeRepo::getId));
    }

    public Path resolveRepoRoot(Long sessionId, Integer repoIndex, String localPath) {
        List<WbSessionCodeRepo> repos = listRepos(sessionId);
        if (repos.isEmpty()) {
            throw new BizException("当前会话未配置本地代码仓库");
        }
        if (StringUtils.hasText(localPath)) {
            String want = Path.of(localPath.trim()).toAbsolutePath().normalize().toString();
            for (WbSessionCodeRepo r : repos) {
                Path root = RepoPathSandbox.requireRepoRoot(r.getLocalPath());
                if (root.toString().equalsIgnoreCase(want) || root.toString().equals(want)) {
                    return root;
                }
            }
            throw new BizException("localPath 不在会话绑定仓库列表中");
        }
        int idx = repoIndex == null ? 0 : repoIndex;
        if (idx < 0 || idx >= repos.size()) {
            throw new BizException("repoIndex 越界，有效范围 0.." + (repos.size() - 1));
        }
        return RepoPathSandbox.requireRepoRoot(repos.get(idx).getLocalPath());
    }

    public CodeRepoDtos.ListResult listDir(Long sessionId, Integer repoIndex, String localPath, String relativePath) {
        // 只读：始终本机，不经 Docker
        Path root = resolveRepoRoot(sessionId, repoIndex, localPath);
        String rel = StringUtils.hasText(relativePath) ? relativePath : ".";
        Path dir = ".".equals(rel) || rel.isBlank()
                ? root
                : RepoPathSandbox.resolveInRepo(root, rel, true);
        if (!Files.isDirectory(dir)) {
            throw new BizException("不是目录：" + rel);
        }
        CodeRepoDtos.ListResult result = new CodeRepoDtos.ListResult();
        result.setRepoPath(root.toString());
        result.setRelativePath(RepoPathSandbox.toRelative(root, dir));
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (count++ >= MAX_LIST) {
                    break;
                }
                CodeRepoDtos.ListResult.Entry e = new CodeRepoDtos.ListResult.Entry();
                e.setName(p.getFileName().toString());
                e.setRelativePath(RepoPathSandbox.toRelative(root, p));
                e.setDirectory(Files.isDirectory(p));
                if (!e.isDirectory()) {
                    try {
                        e.setSize(Files.size(p));
                    } catch (Exception ignored) {
                    }
                }
                result.getEntries().add(e);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("列目录失败：" + e.getMessage());
        }
        audit(sessionId, "list_dir", root, result.getRelativePath(), "ok");
        return result;
    }

    public CodeRepoDtos.ReadResult readFile(
            Long sessionId, Integer repoIndex, String localPath, String relativePath,
            Integer startLine, Integer endLine, Integer maxChars) {
        // 只读：始终本机
        Path root = resolveRepoRoot(sessionId, repoIndex, localPath);
        Path file = RepoPathSandbox.resolveInRepo(root, relativePath, true);
        if (!Files.isRegularFile(file)) {
            throw new BizException("不是文件：" + relativePath);
        }
        int limit = maxChars == null ? MAX_READ_CHARS : Math.min(Math.max(maxChars, 1), MAX_READ_CHARS);
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? lines.size() : Math.min(lines.size(), endLine);
            if (from > lines.size()) {
                from = lines.size() + 1;
                to = lines.size();
            }
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
            CodeRepoDtos.ReadResult r = new CodeRepoDtos.ReadResult();
            r.setRepoPath(root.toString());
            r.setRelativePath(RepoPathSandbox.toRelative(root, file));
            r.setContent(sb.toString());
            r.setStartLine(from);
            r.setEndLine(Math.min(to, from + sb.toString().split("\n", -1).length - 1));
            r.setTruncated(truncated);
            audit(sessionId, "read_file", root, r.getRelativePath(), "ok");
            return r;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读文件失败：" + e.getMessage());
        }
    }

    public CodeRepoDtos.GenericResult writeFile(
            Long sessionId, Integer repoIndex, String localPath, String relativePath, String content) {
        // 有 ATTACHED lease 才进容器写；无 lease 仍本机（评审等不占用沙箱）
        if (sandboxCodeBridge.shouldRouteMutating(sessionId)) {
            return sandboxCodeBridge.writeFile(sessionId, relativePath, content);
        }
        Path root = resolveRepoRoot(sessionId, repoIndex, localPath);
        CodeContentGuard.assertWritableRelativePath(relativePath);
        CodeContentGuard.assertContentSafe(content == null ? "" : content, abs -> underRepos(sessionId, abs));
        Path file = RepoPathSandbox.resolveInRepo(root, relativePath, false);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                // 创建后再次确认父目录未逃逸
                RepoPathSandbox.resolveInRepo(root, RepoPathSandbox.toRelative(root, parent), true);
            }
            boolean created = !Files.exists(file);
            Files.writeString(
                    file,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            // 写后再校验 realpath
            Path real = file.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                Files.deleteIfExists(real);
                throw new BizException("写入后检测到路径越界，已回滚");
            }
            CodeRepoDtos.GenericResult r = new CodeRepoDtos.GenericResult();
            r.setMessage(created ? "已新建" : "已更新");
            r.getData().put("relativePath", RepoPathSandbox.toRelative(root, real));
            r.getData().put("repoPath", root.toString());
            r.getData().put("bytes", content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
            audit(sessionId, "write_file", root, relativePath, r.getMessage());
            return r;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("写文件失败：" + e.getMessage());
        }
    }

    public CodeRepoDtos.GenericResult deleteFile(
            Long sessionId, Integer repoIndex, String localPath, String relativePath) {
        if (sandboxCodeBridge.shouldRouteMutating(sessionId)) {
            return sandboxCodeBridge.deleteFile(sessionId, relativePath);
        }
        Path root = resolveRepoRoot(sessionId, repoIndex, localPath);
        CodeContentGuard.assertWritableRelativePath(relativePath);
        Path target = RepoPathSandbox.resolveInRepo(root, relativePath, true);
        try {
            if (Files.isDirectory(target)) {
                try (var s = Files.newDirectoryStream(target)) {
                    if (s.iterator().hasNext()) {
                        throw new BizException("仅允许删除空目录或文件：" + relativePath);
                    }
                }
            }
            Files.delete(target);
            CodeRepoDtos.GenericResult r = new CodeRepoDtos.GenericResult();
            r.setMessage("已删除");
            r.getData().put("relativePath", relativePath);
            r.getData().put("repoPath", root.toString());
            audit(sessionId, "delete_file", root, relativePath, "ok");
            return r;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("删除失败：" + e.getMessage());
        }
    }

    public Map<String, Object> searchInRepo(
            Long sessionId, Integer repoIndex, String localPath, String query) {
        // 只读搜索：始终本机
        Path root = resolveRepoRoot(sessionId, repoIndex, localPath);
        if (!StringUtils.hasText(query)) {
            throw new BizException("query 不能为空");
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        String engine = "line-scan";
        if (ripgrepSearchService.isAvailable()) {
            try {
                List<Path> files = listSearchableFiles(root, 2000);
                var rgHits = ripgrepSearchService.search(query, files);
                if (rgHits != null) {
                    engine = "ripgrep";
                    for (var h : rgHits) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("relativePath", RepoPathSandbox.toRelative(root, h.path));
                        m.put("line", h.line);
                        String text = h.snippet == null ? "" : h.snippet;
                        m.put("text", text.length() > 400 ? text.substring(0, 400) : text);
                        hits.add(m);
                        if (hits.size() >= ripgrepProperties.getMaxHits()) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.info("repo rg search fallback: {}", e.getMessage());
            }
        }
        if (!"ripgrep".equals(engine)) {
            try {
                String q = query.toLowerCase(Locale.ROOT);
                for (Path p : listSearchableFiles(root, 800)) {
                    if (hits.size() >= 40) {
                        break;
                    }
                    try {
                        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size() && hits.size() < 40; i++) {
                            if (lines.get(i).toLowerCase(Locale.ROOT).contains(q)) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("relativePath", RepoPathSandbox.toRelative(root, p));
                                m.put("line", i + 1);
                                String t = lines.get(i);
                                m.put("text", t.length() > 400 ? t.substring(0, 400) : t);
                                hits.add(m);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                throw new BizException("搜索失败：" + e.getMessage());
            }
        }
        audit(sessionId, "search_in_repo", root, query, "hits=" + hits.size());
        return Map.of("repoPath", root.toString(), "engine", engine, "hits", hits);
    }

    public CodeRepoDtos.PortResult checkPort(int port) {
        if (port < 1 || port > 65535) {
            throw new BizException("端口非法");
        }
        CodeRepoDtos.PortResult r = new CodeRepoDtos.PortResult();
        r.setPort(port);
        boolean canBind;
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            canBind = true;
        } catch (Exception e) {
            canBind = false;
        }
        boolean inUse = !canBind;
        if (!inUse) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                inUse = true;
                canBind = false;
            } catch (Exception ignored) {
            }
        }
        List<PortProcessControl.Listener> listeners = List.of();
        try {
            listeners = PortProcessControl.findListeners(port);
        } catch (RuntimeException ignored) {
            listeners = List.of();
        }
        if (!listeners.isEmpty()) {
            inUse = true;
            canBind = false;
        }
        r.setCanBind(canBind);
        r.setInUse(inUse);
        r.setListeners(toPortListeners(listeners));
        String who = PortProcessControl.describe(listeners);
        if (inUse) {
            r.setMessage(who.isBlank() ? "端口已被占用（本机）" : "端口已被占用（本机）：" + who);
        } else {
            r.setMessage("端口空闲（本机可绑定）");
        }
        return r;
    }

    private static List<CodeRepoDtos.PortListener> toPortListeners(List<PortProcessControl.Listener> listeners) {
        List<CodeRepoDtos.PortListener> out = new ArrayList<>();
        for (PortProcessControl.Listener listener : listeners) {
            CodeRepoDtos.PortListener item = new CodeRepoDtos.PortListener();
            item.setPid(listener.pid());
            item.setProtocol(listener.protocol());
            item.setLocalAddress(listener.localAddress());
            item.setImageName(listener.imageName());
            out.add(item);
        }
        return out;
    }

    public CodeRepoDtos.ActionResult runAction(
            Long sessionId, Integer repoIndex, String localPath, String action, Map<String, String> args) {
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (sandboxCodeBridge.shouldRouteMutating(sessionId)
                && !"stop_dev".equals(act)
                && !"check_port".equals(act)
                && !"list_processes".equals(act)
                && !"stop_port".equals(act)) {
            return sandboxCodeBridge.runAction(sessionId, act, args);
        }
        if ("stop_dev".equals(act)) {
            int n = processRegistry.stopAll(sessionId);
            CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
            r.setAction(act);
            r.setExitCode(0);
            r.setStdout("stopped=" + n);
            r.setStderr("");
            audit(sessionId, "stop_dev", null, "-", "stopped=" + n);
            return r;
        }
        if ("check_port".equals(act)) {
            int port = parsePort(args);
            CodeRepoDtos.PortResult pr = checkPort(port);
            CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
            r.setAction(act);
            r.setExitCode(0);
            r.setStdout(pr.getMessage() + " inUse=" + pr.isInUse() + " canBind=" + pr.isCanBind());
            r.setStderr("");
            return r;
        }
        if ("list_processes".equals(act)) {
            CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
            r.setAction(act);
            r.setExitCode(0);
            r.setStdout(processRegistry.list(sessionId).toString());
            r.setStderr("");
            return r;
        }
        if ("stop_port".equals(act)) {
            int port = parsePort(args);
            PortProcessControl.StopOutcome stopped = PortProcessControl.stopListeners(port);
            CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
            r.setAction(act);
            r.setExitCode(stopped.exitCode());
            r.setStdout(stopped.message());
            r.setStderr("");
            audit(sessionId, "stop_port", null, "port=" + port, stopped.message());
            return r;
        }

        Path root = resolveRepoRoot(sessionId, repoIndex, localPath);
        List<String> cmd = buildCommand(root, act, args == null ? Map.of() : args);
        boolean background = isStartAction(act);
        int timeout = background ? START_TIMEOUT_SEC : DEFAULT_TIMEOUT_SEC;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            if (background) {
                processRegistry.register(sessionId, act, root.toString(), p);
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                Thread tOut = drain(p.getInputStream(), stdout);
                Thread tErr = drain(p.getErrorStream(), stderr);
                p.waitFor(timeout, TimeUnit.SECONDS);
                // 进程若仍存活，drain 线程会继续消费输出，避免管道堵死
                try {
                    tOut.join(300);
                    tErr.join(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
                r.setAction(act);
                r.setRepoPath(root.toString());
                r.setBackground(true);
                r.setPid(p.pid());
                r.setExitCode(p.isAlive() ? -1 : p.exitValue());
                r.setStdout(trimOut(stdout.toString()));
                r.setStderr(trimOut(stderr.toString()));
                r.setTimedOut(false);
                audit(sessionId, act, root, String.join(" ", cmd), "pid=" + p.pid());
                return r;
            }
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread tOut = drain(p.getInputStream(), stdout);
            Thread tErr = drain(p.getErrorStream(), stderr);
            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
                r.setAction(act);
                r.setRepoPath(root.toString());
                r.setTimedOut(true);
                r.setExitCode(-1);
                r.setStdout(trimOut(stdout.toString()));
                r.setStderr(trimOut(stderr.toString()) + "\n(timeout " + timeout + "s)");
                audit(sessionId, act, root, String.join(" ", cmd), "timeout");
                return r;
            }
            tOut.join(1000);
            tErr.join(1000);
            CodeRepoDtos.ActionResult r = new CodeRepoDtos.ActionResult();
            r.setAction(act);
            r.setRepoPath(root.toString());
            r.setExitCode(p.exitValue());
            r.setStdout(trimOut(stdout.toString()));
            r.setStderr(trimOut(stderr.toString()));
            audit(sessionId, act, root, String.join(" ", cmd), "exit=" + p.exitValue());
            return r;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("执行失败：" + e.getMessage());
        }
    }

    private List<String> buildCommand(Path root, String action, Map<String, String> args) {
        boolean win = RepoPathSandbox.isWindows();
        return switch (action) {
            case "tool_version" -> versionCommand(args.getOrDefault("tool", "java"), win);
            case "git_status" -> List.of("git", "status", "--short");
            case "git_diff" -> {
                String path = args.get("path");
                if (StringUtils.hasText(path)) {
                    RepoPathSandbox.resolveInRepo(root, path, false);
                    yield List.of("git", "diff", "--stat", "--", path.replace('\\', '/'));
                }
                yield List.of("git", "diff", "--stat");
            }
            case "git_log" -> List.of("git", "log", "-n", "20", "--oneline");
            case "build_gradle" -> gradle(root, win, List.of("build", "-x", "test"));
            case "test_gradle" -> gradle(root, win, List.of("test"));
            case "start_gradle" -> gradle(root, win, List.of("bootRun"));
            case "build_maven" -> List.of(mvn(win), "-q", "-DskipTests", "package");
            case "test_maven" -> List.of(mvn(win), "-q", "test");
            case "start_maven" -> List.of(mvn(win), "-q", "spring-boot:run");
            case "mvn_validate" -> List.of(mvn(win), "-q", "validate");
            case "mvn_deps_tree" -> List.of(mvn(win), "-q", "dependency:tree");
            case "gradle_deps" -> gradle(root, win, List.of("dependencies"));
            case "build_vite" -> npmOrPnpm(root, win, List.of("run", "build"));
            case "test_node" -> npmOrPnpm(root, win, List.of("test"));
            case "start_vite" -> npmOrPnpm(root, win, List.of("run", "dev"));
            case "test_uv" -> {
                String path = args.get("path");
                if (StringUtils.hasText(path)) {
                    RepoPathSandbox.resolveInRepo(root, path, false);
                    yield List.of(uv(win), "run", "pytest", "-q", path.replace('\\', '/'));
                }
                yield List.of(uv(win), "run", "pytest", "-q");
            }
            case "start_uv" -> {
                String entry = args.getOrDefault("entry", "dap-agent");
                if (!entry.matches("^[a-zA-Z0-9._-]{1,64}$")) {
                    throw new BizException("uv 入口名非法");
                }
                yield List.of(uv(win), "run", entry);
            }
            case "build", "test", "start_dev" -> autoDetect(root, win, action);
            default -> throw new BizException("不支持的动作（不在白名单）：" + action);
        };
    }

    private List<String> autoDetect(Path root, boolean win, String kind) {
        boolean hasGradlew = Files.exists(root.resolve(win ? "gradlew.bat" : "gradlew"))
                || Files.exists(root.resolve("build.gradle"))
                || Files.exists(root.resolve("build.gradle.kts"));
        boolean hasPom = Files.exists(root.resolve("pom.xml"));
        boolean hasPkg = Files.exists(root.resolve("package.json"));
        boolean hasPy = Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("pytest.ini"));
        return switch (kind) {
            case "build" -> {
                if (hasGradlew) {
                    yield gradle(root, win, List.of("build", "-x", "test"));
                }
                if (hasPom) {
                    yield List.of(mvn(win), "-q", "-DskipTests", "package");
                }
                if (hasPkg) {
                    yield npmOrPnpm(root, win, List.of("run", "build"));
                }
                throw new BizException("无法自动识别构建系统，请使用 build_gradle/build_maven/build_vite");
            }
            case "test" -> {
                if (hasGradlew) {
                    yield gradle(root, win, List.of("test"));
                }
                if (hasPom) {
                    yield List.of(mvn(win), "-q", "test");
                }
                if (hasPkg) {
                    yield npmOrPnpm(root, win, List.of("test"));
                }
                if (hasPy) {
                    yield List.of(uv(win), "run", "pytest", "-q");
                }
                throw new BizException("无法自动识别测试命令");
            }
            case "start_dev" -> {
                if (hasGradlew) {
                    yield gradle(root, win, List.of("bootRun"));
                }
                if (hasPom) {
                    yield List.of(mvn(win), "-q", "spring-boot:run");
                }
                if (hasPkg) {
                    yield npmOrPnpm(root, win, List.of("run", "dev"));
                }
                if (hasPy) {
                    yield List.of(uv(win), "run", "dap-agent");
                }
                throw new BizException("无法自动识别启动命令");
            }
            default -> throw new BizException("未知动作");
        };
    }

    private List<String> versionCommand(String tool, boolean win) {
        String t = tool.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "java" -> List.of("java", "-version");
            case "javac" -> List.of("javac", "-version");
            case "mvn", "maven" -> List.of(mvn(win), "-v");
            case "gradle" -> List.of(win ? "gradle.bat" : "gradle", "-v");
            case "node" -> List.of("node", "-v");
            case "npm" -> List.of(win ? "npm.cmd" : "npm", "-v");
            case "pnpm" -> List.of(win ? "pnpm.cmd" : "pnpm", "-v");
            case "uv" -> List.of(uv(win), "--version");
            case "python" -> List.of(win ? "python" : "python3", "--version");
            default -> throw new BizException("tool_version 不支持: " + tool);
        };
    }

    private List<String> gradle(Path root, boolean win, List<String> tasks) {
        Path gw = root.resolve(win ? "gradlew.bat" : "gradlew");
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(gw) || (win && Files.exists(gw))) {
            cmd.add(gw.toAbsolutePath().toString());
        } else {
            cmd.add(win ? "gradle.bat" : "gradle");
        }
        cmd.addAll(tasks);
        return cmd;
    }

    private List<String> npmOrPnpm(Path root, boolean win, List<String> args) {
        boolean pnpm = Files.exists(root.resolve("pnpm-lock.yaml"));
        String exe = pnpm ? (win ? "pnpm.cmd" : "pnpm") : (win ? "npm.cmd" : "npm");
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(args);
        // 仅允许 run build/dev 或 test
        if (args.size() >= 1) {
            String a0 = args.get(0);
            if ("run".equals(a0)) {
                if (args.size() < 2 || !List.of("build", "dev", "test").contains(args.get(1))) {
                    throw new BizException("npm/pnpm 仅允许 run build|dev|test");
                }
            } else if (!"test".equals(a0)) {
                throw new BizException("npm/pnpm 参数不在白名单");
            }
        }
        return cmd;
    }

    private static String mvn(boolean win) {
        return win ? "mvn.cmd" : "mvn";
    }

    private static String uv(boolean win) {
        return "uv";
    }

    private static boolean isStartAction(String act) {
        return act.startsWith("start_") || "start_dev".equals(act);
    }

    private static int parsePort(Map<String, String> args) {
        if (args == null || !args.containsKey("port")) {
            throw new BizException("check_port 需要 args.port");
        }
        try {
            int p = Integer.parseInt(args.get("port").trim());
            if (p < 1 || p > 65535) {
                throw new BizException("端口非法");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new BizException("端口非法");
        }
    }

    private boolean underRepos(Long sessionId, String absHint) {
        if (!StringUtils.hasText(absHint)) {
            return false;
        }
        try {
            Path hint = Path.of(absHint.trim()).toAbsolutePath().normalize();
            for (WbSessionCodeRepo r : listRepos(sessionId)) {
                Path root = RepoPathSandbox.requireRepoRoot(r.getLocalPath());
                if (hint.startsWith(root)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String trimOut(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, OUTPUT_CHARS) + "\n...[truncated]";
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sink.length() < OUTPUT_CHARS) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        }, "code-repo-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git",
            ".svn",
            ".hg",
            ".venv",
            "venv",
            "node_modules",
            "target",
            "build",
            "dist",
            "out",
            "__pycache__",
            ".idea",
            ".vscode",
            ".gradle",
            "coverage",
            ".tox",
            ".mypy_cache",
            ".pytest_cache");

    private static List<Path> listSearchableFiles(Path root, int limit) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isSkippedPath(root, p))
                    .limit(limit)
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isSkippedPath(Path root, Path path) {
        Path rel;
        try {
            rel = root.relativize(path.toAbsolutePath().normalize());
        } catch (Exception e) {
            return true;
        }
        for (Path part : rel) {
            String name = part.toString();
            if (SKIP_DIR_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".class")
                || fileName.endsWith(".jar")
                || fileName.endsWith(".war")
                || fileName.endsWith(".png")
                || fileName.endsWith(".jpg")
                || fileName.endsWith(".jpeg")
                || fileName.endsWith(".gif")
                || fileName.endsWith(".webp")
                || fileName.endsWith(".ico")
                || fileName.endsWith(".pdf")
                || fileName.endsWith(".zip")
                || fileName.endsWith(".lock");
    }

    private void audit(Long sessionId, String action, Path repo, String detail, String result) {
        log.info(
                "code-repo audit sessionId={} action={} repo={} detail={} result={}",
                sessionId,
                action,
                repo == null ? "-" : repo,
                detail,
                result);
    }
}
