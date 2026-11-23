package com.lonple.dap.server.modules.review.git;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.review.credential.CredentialStore;
import com.lonple.dap.server.modules.review.partition.DiffFile;
import com.lonple.dap.server.modules.review.partition.DiffParser;
import com.lonple.dap.server.modules.review.store.TaskPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class GitService {
    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private final TaskPaths taskPaths;
    private final CodeDirLock codeDirLock;

    public GitService(TaskPaths taskPaths, CodeDirLock codeDirLock) {
        this.taskPaths = taskPaths;
        this.codeDirLock = codeDirLock;
    }

    public boolean gitAvailable() {
        try {
            ExecResult r = exec(List.of("git", "--version"), null, 15);
            return r.exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Path resolveLocalWorkTree(String localPath) {
        if (!StringUtils.hasText(localPath)) {
            throw new BizException("本地路径不能为空");
        }
        Path p = Path.of(localPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new BizException("本地路径不存在: " + p);
        }
        if (!Files.isDirectory(p.resolve(".git")) && !Files.exists(p.resolve(".git"))) {
            throw new BizException("不是 Git 仓库: " + p);
        }
        return p;
    }

    public VerifyOutcome verifyLocal(String localPath) {
        Path work = resolveLocalWorkTree(localPath);
        ExecResult fetch = exec(List.of("git", "fetch", "--all", "--prune"), work, 120);
        if (fetch.exitCode != 0) {
            throw new BizException("git fetch 失败: " + fetch.stderrOrStdout());
        }
        List<String> branches = listBranches(work);
        return new VerifyOutcome(work.toString(), null, branches, fetch.stdout);
    }

    public VerifyOutcome verifyRemote(String remoteUrl, CredentialStore.Cred cred) {
        String codeKey = GitUrlNormalizer.toCodeKey(remoteUrl);
        Path codeDir = taskPaths.codeDir(codeKey);
        String[] outcome = new String[1];
        codeDirLock.withLock(codeKey, () -> {
            try {
                Files.createDirectories(codeDir.getParent());
                if (Files.isDirectory(codeDir.resolve(".git")) || Files.exists(codeDir.resolve(".git"))) {
                    fetchRemote(codeDir, remoteUrl, cred);
                    outcome[0] = "reused";
                } else {
                    if (Files.exists(codeDir)) {
                        // empty or broken dir
                        try (var walk = Files.walk(codeDir)) {
                            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                        }
                    }
                    List<String> cmd = new ArrayList<>();
                    cmd.add("git");
                    cmd.add("clone");
                    cmd.add("--");
                    cmd.add(authUrl(remoteUrl, cred) != null ? authUrl(remoteUrl, cred) : remoteUrl.trim());
                    cmd.add(codeDir.toString());
                    ExecResult clone = exec(cmd, null, 300);
                    if (clone.exitCode != 0) {
                        throw new BizException("git clone 失败: " + clone.stderrOrStdout());
                    }
                    // 立刻去掉凭据
                    exec(List.of("git", "remote", "set-url", "origin", stripUserInfo(remoteUrl)), codeDir, 15);
                    outcome[0] = "cloned";
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(500, e.getMessage());
            }
        });
        List<String> branches = listBranches(codeDir);
        return new VerifyOutcome(codeDir.toString(), codeKey, branches, outcome[0]);
    }

    public List<String> listBranches(Path workTree) {
        ExecResult r = exec(List.of("git", "branch", "-a", "--format=%(refname:short)"), workTree, 30);
        if (r.exitCode != 0) {
            throw new BizException("列举分支失败: " + r.stderrOrStdout());
        }
        Set<String> set = new LinkedHashSet<>();
        for (String line : r.stdout.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.endsWith("/HEAD")) {
                continue;
            }
            if (t.startsWith("remotes/")) {
                t = t.substring("remotes/".length());
                int slash = t.indexOf('/');
                if (slash > 0) {
                    t = t.substring(slash + 1);
                }
            }
            if (t.startsWith("origin/")) {
                t = t.substring("origin/".length());
            }
            if (!t.isEmpty() && !"HEAD".equals(t)) {
                set.add(t);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 完整 diff（不截断），拆成按文件列表。分片编排主入口。
     */
    public List<DiffFile> diffFiles(Path workTree, String base, String review) {
        String text = rawDiff(workTree, base, review);
        return DiffParser.parse(text);
    }

    /**
     * @deprecated 整包截断路径已由 {@link #diffFiles} + 分片替代；保留兼容旧调用。
     */
    @Deprecated
    public DiffResult diff(Path workTree, String base, String review) {
        List<DiffFile> files = diffFiles(workTree, base, review);
        String text = files.isEmpty() ? "" : com.lonple.dap.server.modules.review.partition.ReviewShard.joinPatches(files);
        return new DiffResult(text, files.size(), false);
    }

    public String rawDiff(Path workTree, String base, String review) {
        assertBranches(base, review);
        ExecResult r = exec(List.of("git", "diff", "--find-renames", base + "..." + review), workTree, 120);
        if (r.exitCode != 0) {
            throw new BizException("git diff 失败: " + r.stderrOrStdout());
        }
        return r.stdout == null ? "" : r.stdout;
    }

    /**
     * 取 base...review 区间提交说明（新→旧），供业务主题提取。
     */
    public List<String> commitMessages(Path workTree, String base, String review) {
        return commitMessages(workTree, base, review, null);
    }

    /**
     * 仅包含碰过 {@code paths} 的提交说明；paths 为空则等同全量。
     */
    public List<String> commitMessages(Path workTree, String base, String review, List<String> paths) {
        assertBranches(base, review);
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("log");
        cmd.add("--no-merges");
        cmd.add("--pretty=format:%s%n%b%n---");
        cmd.add(base + "..." + review);
        if (paths != null && !paths.isEmpty()) {
            cmd.add("--");
            for (String p : paths) {
                if (StringUtils.hasText(p)) {
                    cmd.add(p);
                }
            }
        }
        ExecResult r = exec(cmd, workTree, 60);
        if (r.exitCode != 0) {
            throw new BizException("git log 失败: " + r.stderrOrStdout());
        }
        String raw = r.stdout == null ? "" : r.stdout.trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String block : raw.split("\\n---\\s*")) {
            String t = block.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
            if (out.size() >= 30) {
                break;
            }
        }
        return out;
    }

    private static void assertBranches(String base, String review) {
        if (!StringUtils.hasText(base) || !StringUtils.hasText(review)) {
            throw new BizException("基准分支与评审分支不能为空");
        }
        if (base.equals(review)) {
            throw new BizException("基准分支与评审分支不能相同");
        }
    }

    private void fetchRemote(Path codeDir, String remoteUrl, CredentialStore.Cred cred) {
        String auth = authUrl(remoteUrl, cred);
        if (auth == null) {
            ExecResult fetch = exec(List.of("git", "fetch", "--all", "--prune"), codeDir, 180);
            if (fetch.exitCode != 0) {
                throw new BizException("git fetch 失败: " + fetch.stderrOrStdout());
            }
            return;
        }
        String original = null;
        try {
            ExecResult getUrl = exec(List.of("git", "remote", "get-url", "origin"), codeDir, 15);
            if (getUrl.exitCode == 0) {
                original = getUrl.stdout.trim();
            }
            ExecResult setAuth = exec(List.of("git", "remote", "set-url", "origin", auth), codeDir, 15);
            if (setAuth.exitCode != 0) {
                throw new BizException("设置临时远程地址失败: " + setAuth.stderrOrStdout());
            }
            ExecResult fetch = exec(List.of("git", "fetch", "--all", "--prune"), codeDir, 180);
            if (fetch.exitCode != 0) {
                throw new BizException("git fetch 失败: " + fetch.stderrOrStdout());
            }
        } finally {
            String restore = StringUtils.hasText(original) ? stripUserInfo(original) : stripUserInfo(remoteUrl);
            if (StringUtils.hasText(restore)) {
                exec(List.of("git", "remote", "set-url", "origin", restore), codeDir, 15);
            }
        }
    }

    private static String stripUserInfo(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return u;
        }
        String scheme = u.startsWith("https://") ? "https://" : "http://";
        String rest = u.substring(scheme.length());
        int at = rest.lastIndexOf('@');
        int slash = rest.indexOf('/');
        if (at > 0 && (slash < 0 || at < slash)) {
            rest = rest.substring(at + 1);
        }
        return scheme + rest;
    }

    /**
     * 仅用于进程内 git 调用；切勿写入日志或 config。
     */
    private String authUrl(String remoteUrl, CredentialStore.Cred cred) {
        if (cred == null || (!StringUtils.hasText(cred.username()) && !StringUtils.hasText(cred.password()))) {
            return null;
        }
        String url = remoteUrl.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        try {
            String scheme = url.startsWith("https://") ? "https://" : "http://";
            String rest = url.substring(scheme.length());
            // strip existing userinfo
            int at = rest.lastIndexOf('@');
            int slash = rest.indexOf('/');
            if (at > 0 && (slash < 0 || at < slash)) {
                rest = rest.substring(at + 1);
            }
            String user = URLEncoder.encode(cred.username() == null ? "" : cred.username(), StandardCharsets.UTF_8);
            String pass = URLEncoder.encode(cred.password() == null ? "" : cred.password(), StandardCharsets.UTF_8);
            return scheme + user + ":" + pass + "@" + rest;
        } catch (Exception e) {
            log.warn("build auth url failed: {}", e.getMessage());
            return null;
        }
    }

    private ExecResult exec(List<String> command, Path workDir, int timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(false);
            // avoid interactive credential prompts hanging
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process p = pb.start();
            String stdout;
            String stderr;
            try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder so = new StringBuilder();
                StringBuilder se = new StringBuilder();
                Thread tOut = new Thread(() -> readAll(out, so));
                Thread tErr = new Thread(() -> readAll(err, se));
                tOut.start();
                tErr.start();
                boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
                tOut.join(2000);
                tErr.join(2000);
                if (!finished) {
                    p.destroyForcibly();
                    throw new BizException("git 命令超时: " + String.join(" ", redact(command)));
                }
                stdout = so.toString();
                stderr = se.toString();
                return new ExecResult(p.exitValue(), stdout, stderr);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "执行 git 失败: " + e.getMessage());
        }
    }

    private static void readAll(BufferedReader reader, StringBuilder sb) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private static List<String> redact(List<String> command) {
        List<String> out = new ArrayList<>();
        for (String c : command) {
            if (c != null && (c.contains("://") && c.contains("@"))) {
                out.add(c.replaceAll("://[^@]+@", "://***@"));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    public record VerifyOutcome(String workTreePath, String codeKey, List<String> branches, String note) {
    }

    public record DiffResult(String text, int fileCount, boolean truncated) {
    }

    private record ExecResult(int exitCode, String stdout, String stderr) {
        String stderrOrStdout() {
            if (stderr != null && !stderr.isBlank()) {
                return stderr.trim();
            }
            return stdout == null ? "" : stdout.trim();
        }
    }
}
