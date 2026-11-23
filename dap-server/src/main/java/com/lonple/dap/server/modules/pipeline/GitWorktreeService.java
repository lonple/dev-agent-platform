package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 产品配置的是<strong>本地物理路径</strong>，且该目录须为已初始化的 Git 仓库。
 * 需求编码：{@code feat/{需求编号}}；问题修复：{@code fix/{问题单号}}。
 * 用 worktree 挂到独立工作目录，避免多任务共用同一物理仓时互相覆盖；本期不合主干。
 */
@Slf4j
@Service
public class GitWorktreeService {

    public record WorktreeResult(String branchName, String worktreePath, String baseBranch) {}

    public enum BranchPrefix {
        FEAT("feat"),
        FIX("fix");

        private final String value;

        BranchPrefix(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** 业务仓分支：{@code feat/REQ-xxx}；测试仓：{@code feat/REQ-xxx-test} */
    public static String branchNameFor(String refNo, String repoKind) {
        return branchNameFor(refNo, repoKind, BranchPrefix.FEAT);
    }

    public static String branchNameFor(String refNo, String repoKind, BranchPrefix prefix) {
        if (!StringUtils.hasText(refNo)) {
            throw new BizException("编号为空，无法创建编码分支");
        }
        BranchPrefix p = prefix == null ? BranchPrefix.FEAT : prefix;
        String safeRef = sanitize(refNo);
        String kindSuffix = StringUtils.hasText(repoKind) && !"BIZ".equalsIgnoreCase(repoKind)
                ? "-" + repoKind.toLowerCase()
                : "";
        return p.value() + "/" + safeRef + kindSuffix;
    }

    public WorktreeResult ensureWorktree(String baseLocalPath, String refNo, String repoKind) {
        return ensureWorktree(baseLocalPath, refNo, repoKind, BranchPrefix.FEAT);
    }

    /**
     * 在产品物理 Git 仓上确保：分支已创建，并挂载到独立 worktree 目录。
     */
    public WorktreeResult ensureWorktree(
            String baseLocalPath, String refNo, String repoKind, BranchPrefix prefix) {
        if (!StringUtils.hasText(baseLocalPath)) {
            throw new BizException("产品代码仓库本地路径为空");
        }
        Path base = Path.of(baseLocalPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new BizException("代码仓库目录不存在：" + base);
        }
        Path gitMeta = base.resolve(".git");
        if (!Files.exists(gitMeta)) {
            throw new BizException("配置的物理路径不是 Git 仓库（缺少 .git），请先 git init 或 clone：" + base);
        }

        BranchPrefix p = prefix == null ? BranchPrefix.FEAT : prefix;
        String branch = branchNameFor(refNo, repoKind, p);
        String safeRef = sanitize(refNo);
        String kindSuffix = StringUtils.hasText(repoKind) && !"BIZ".equalsIgnoreCase(repoKind)
                ? "-" + repoKind.toLowerCase()
                : "";
        String dirName = p.value() + "-" + safeRef + kindSuffix;
        Path worktreesRoot = base.getParent() == null
                ? base.resolveSibling(base.getFileName() + "-worktrees")
                : base.getParent().resolve(base.getFileName() + "-worktrees");
        Path worktreePath = worktreesRoot.resolve(dirName).toAbsolutePath().normalize();

        String baseBranch = defaultBranch(base);

        if (Files.isDirectory(worktreePath)) {
            CmdResult cur = runGit(worktreePath, List.of("rev-parse", "--abbrev-ref", "HEAD"), false);
            if (cur.exitCode() == 0 && !branch.equals(cur.output().trim())) {
                runGit(worktreePath, List.of("checkout", branch), false);
            }
            log.info("reuse worktree path={} branch={} baseBranch={}", worktreePath, branch, baseBranch);
            return new WorktreeResult(branch, worktreePath.toString(), baseBranch);
        }

        try {
            Files.createDirectories(worktreesRoot);
        } catch (Exception e) {
            throw new BizException("创建 worktree 父目录失败：" + e.getMessage());
        }

        boolean branchExists = runGit(base, List.of("rev-parse", "--verify", "refs/heads/" + branch), false)
                .exitCode() == 0;

        List<String> args = new ArrayList<>();
        args.add("worktree");
        args.add("add");
        if (!branchExists) {
            args.add("-b");
            args.add(branch);
            args.add(worktreePath.toString());
            args.add(baseBranch);
        } else {
            args.add(worktreePath.toString());
            args.add(branch);
        }
        CmdResult add = runGit(base, args, true);
        if (add.exitCode() != 0) {
            throw new BizException("创建 git worktree 失败：" + add.output());
        }
        log.info("created worktree path={} branch={} baseBranch={}", worktreePath, branch, baseBranch);
        return new WorktreeResult(branch, worktreePath.toString(), baseBranch);
    }

    private static String sanitize(String refNo) {
        return refNo.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    public String defaultBranch(Path base) {
        CmdResult sym = runGit(base, List.of("symbolic-ref", "refs/remotes/origin/HEAD"), false);
        if (sym.exitCode() == 0 && StringUtils.hasText(sym.output())) {
            String line = sym.output().trim();
            int idx = line.lastIndexOf('/');
            if (idx >= 0 && idx < line.length() - 1) {
                return line.substring(idx + 1);
            }
        }
        for (String cand : List.of("main", "master", "develop")) {
            if (runGit(base, List.of("rev-parse", "--verify", "refs/heads/" + cand), false).exitCode() == 0) {
                return cand;
            }
        }
        CmdResult cur = runGit(base, List.of("rev-parse", "--abbrev-ref", "HEAD"), false);
        if (cur.exitCode() == 0 && StringUtils.hasText(cur.output())) {
            return cur.output().trim();
        }
        throw new BizException("无法确定仓库默认分支：" + base);
    }

    /** 解析 ref 为完整 commit SHA（如 {@code HEAD}、分支名）。 */
    public String revParse(Path repo, String ref) {
        if (repo == null || !Files.isDirectory(repo)) {
            throw new BizException("仓库路径无效，无法 rev-parse：" + repo);
        }
        String target = StringUtils.hasText(ref) ? ref.trim() : "HEAD";
        Path base = repo.toAbsolutePath().normalize();
        CmdResult r = runGit(base, List.of("rev-parse", target), false);
        String out = r.output() == null ? "" : r.output().trim();
        String sha = out.lines().findFirst().orElse("").trim();
        if (r.exitCode() != 0 || !StringUtils.hasText(sha)) {
            throw new BizException("git rev-parse 失败：" + target + " → " + out);
        }
        return sha;
    }

    /**
     * 代码评审对比基线：固定优先 {@code master}（本地或 origin），否则回退 {@link #defaultBranch}。
     */
    public String reviewBaseBranch(Path repo) {
        Path base = repo.toAbsolutePath().normalize();
        if (runGit(base, List.of("rev-parse", "--verify", "refs/heads/master"), false).exitCode() == 0) {
            return "master";
        }
        if (runGit(base, List.of("rev-parse", "--verify", "refs/remotes/origin/master"), false).exitCode() == 0) {
            return "origin/master";
        }
        String fallback = defaultBranch(base);
        log.warn("review base: master missing at {}, fallback={}", base, fallback);
        return fallback;
    }

    /** worktree 是否有未提交改动（含未跟踪）。 */
    public boolean isDirty(Path workTree) {
        if (workTree == null || !Files.isDirectory(workTree)) {
            return false;
        }
        Path wt = workTree.toAbsolutePath().normalize();
        CmdResult status = runGit(wt, List.of("status", "--porcelain"), false);
        return status.exitCode() == 0 && StringUtils.hasText(status.output().trim());
    }

    /**
     * 将 worktree 中未提交改动全部入库（含未跟踪文件）。干净工作区则跳过。
     *
     * @return true 若产生了新 commit
     */
    public boolean commitAllIfDirty(Path workTree, String message) {
        if (workTree == null || !Files.isDirectory(workTree)) {
            throw new BizException("worktree 不存在，无法 commit：" + workTree);
        }
        Path wt = workTree.toAbsolutePath().normalize();
        if (!isDirty(wt)) {
            log.info("git commit skip (clean) path={}", wt);
            return false;
        }
        CmdResult add = runGit(wt, List.of("add", "-A"), true);
        if (add.exitCode() != 0) {
            throw new BizException("git add 失败：" + add.output());
        }
        String msg = StringUtils.hasText(message) ? message.trim() : "pipeline auto-commit";
        CmdResult commit = runGit(wt, List.of(
                "-c", "user.name=DAP Agent",
                "-c", "user.email=dap@localhost",
                "commit",
                "-m", msg), true);
        if (commit.exitCode() != 0) {
            // 并发/钩子可能导致「nothing to commit」
            CmdResult again = runGit(wt, List.of("status", "--porcelain"), false);
            if (again.exitCode() == 0 && !StringUtils.hasText(again.output().trim())) {
                log.info("git commit noop after add path={}", wt);
                return false;
            }
            throw new BizException("git commit 失败：" + commit.output());
        }
        log.info("git commit ok path={} message={}", wt, msg);
        return true;
    }

    private record CmdResult(int exitCode, String output) {}

    private CmdResult runGit(Path cwd, List<String> args, boolean logFail) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = br.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new BizException("git 命令超时：" + String.join(" ", cmd));
            }
            int code = p.exitValue();
            if (code != 0 && logFail) {
                log.warn("git fail cwd={} cmd={} out={}", cwd, cmd, out);
            }
            return new CmdResult(code, out == null ? "" : out);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("执行 git 失败：" + e.getMessage());
        }
    }
}
