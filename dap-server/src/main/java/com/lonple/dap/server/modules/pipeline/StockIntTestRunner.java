package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.modules.agenttool.dto.CodeRepoDtos;
import com.lonple.dap.server.modules.agenttool.repo.CodeRepoToolService;
import com.lonple.dap.server.modules.pipeline.entity.PipeRepoWorktree;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 存量集成测试：由平台直接跑自动化，不经 LLM 探仓。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockIntTestRunner {

    private static final Pattern JUNIT_FAIL = Pattern.compile(
            "<(?:failure|error)\\b[^>]*?(?:message=\"([^\"]*)\")?[^>]*>([\\s\\S]*?)</(?:failure|error)>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TESTCASE_NAME = Pattern.compile(
            "<testcase\\b[^>]*\\bclassname=\"([^\"]+)\"[^>]*\\bname=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private final CodeRepoToolService codeRepoToolService;

    public record Outcome(
            boolean passed,
            int exitCode,
            String repoPath,
            String action,
            String reportMarkdown,
            String compactFailureSummary) {
    }

    public Outcome run(Long sessionId, List<PipeRepoWorktree> worktrees) {
        String localPath = pickRepoPath(worktrees);
        String action = "test";
        CodeRepoDtos.ActionResult result;
        try {
            result = codeRepoToolService.runAction(
                    sessionId, null, localPath, action, Map.of());
        } catch (Exception e) {
            log.warn("stock int-test run failed session={}: {}", sessionId, e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String report = buildReport(false, -1, localPath == null ? "-" : localPath, action,
                    "", msg, "平台执行测试失败：" + msg);
            return new Outcome(false, -1, localPath, action, report, msg);
        }
        int exit = result.getExitCode();
        boolean passed = exit == 0;
        String stdout = nullToEmpty(result.getStdout());
        String stderr = nullToEmpty(result.getStderr());
        String repoPath = StringUtils.hasText(result.getRepoPath()) ? result.getRepoPath() : localPath;
        String junitSummary = summarizeJUnitFailures(repoPath);
        String compact = buildCompactFailure(passed, exit, stderr, stdout, junitSummary);
        String report = buildReport(passed, exit, repoPath, action, stdout, stderr, junitSummary);
        log.info("stock int-test done session={} repo={} action={} exit={} passed={}",
                sessionId, repoPath, action, exit, passed);
        return new Outcome(passed, exit, repoPath, action, report, compact);
    }

    private static String pickRepoPath(List<PipeRepoWorktree> worktrees) {
        if (worktrees == null || worktrees.isEmpty()) {
            return null;
        }
        return worktrees.stream()
                .filter(w -> w != null && StringUtils.hasText(w.getWorktreePath()))
                .sorted(Comparator.comparing((PipeRepoWorktree w) -> {
                    String k = w.getRepoKind() == null ? "" : w.getRepoKind().toUpperCase(Locale.ROOT);
                    if ("TEST".equals(k)) {
                        return 0;
                    }
                    if ("BIZ".equals(k) || "CODE".equals(k)) {
                        return 1;
                    }
                    return 2;
                }))
                .map(PipeRepoWorktree::getWorktreePath)
                .findFirst()
                .orElse(null);
    }

    private static String buildReport(
            boolean passed,
            int exitCode,
            String repoPath,
            String action,
            String stdout,
            String stderr,
            String junitSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 集成测试报告\n\n");
        sb.append("> 本报告由平台直接执行存量自动化生成（不经 Agent 探仓）。\n\n");
        sb.append("## 0. 文档信息\n\n");
        sb.append("| 项 | 内容 |\n|---|---|\n");
        sb.append("| 执行日期 | ").append(LocalDate.now()).append(" |\n");
        sb.append("| 仓库 | `").append(nullToEmpty(repoPath)).append("` |\n");
        sb.append("| 动作 | `").append(action).append("` |\n");
        sb.append("| exitCode | ").append(exitCode).append(" |\n\n");
        sb.append("## 3. 结论\n\n");
        sb.append(passed ? "**结论：通过**\n\n" : "**结论：不通过**\n\n");
        if (!passed) {
            sb.append("## 4. 失败摘要\n\n");
            if (StringUtils.hasText(junitSummary)) {
                sb.append(junitSummary).append("\n\n");
            }
            sb.append("### stderr（截断）\n\n```\n")
                    .append(trimBlock(stderr, 6000))
                    .append("\n```\n\n");
            if (StringUtils.hasText(stdout)) {
                sb.append("### stdout（截断）\n\n```\n")
                        .append(trimBlock(stdout, 3000))
                        .append("\n```\n");
            }
        } else {
            sb.append("## 4. 覆盖摘要\n\n平台执行 `").append(action).append("` 退出码 0。\n");
        }
        return sb.toString();
    }

    private static String buildCompactFailure(
            boolean passed, int exitCode, String stderr, String stdout, String junitSummary) {
        if (passed) {
            return "全部通过";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("exitCode=").append(exitCode).append('\n');
        if (StringUtils.hasText(junitSummary)) {
            sb.append(junitSummary).append('\n');
        }
        String tail = StringUtils.hasText(stderr) ? stderr : stdout;
        if (StringUtils.hasText(tail)) {
            sb.append(trimBlock(tail, 2500));
        }
        return sb.toString().trim();
    }

    /** 扫描 worktree 下近期 JUnit XML，摘失败用例。 */
    static String summarizeJUnitFailures(String repoPath) {
        if (!StringUtils.hasText(repoPath)) {
            return "";
        }
        Path root = Path.of(repoPath);
        if (!Files.isDirectory(root)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 12)) {
            List<Path> xmls = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("TEST-") && n.endsWith(".xml");
                    })
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.contains("/test-results/") || s.contains("/surefire-reports/");
                    })
                    .limit(80)
                    .toList();
            for (Path xml : xmls) {
                String text = Files.readString(xml);
                if (!text.contains("<failure") && !text.contains("<error")) {
                    continue;
                }
                Matcher tm = TESTCASE_NAME.matcher(text);
                String className = xml.getFileName().toString();
                String method = "";
                if (tm.find()) {
                    className = tm.group(1);
                    method = tm.group(2);
                }
                Matcher fm = JUNIT_FAIL.matcher(text);
                if (fm.find()) {
                    String msg = fm.group(1) != null ? fm.group(1) : "";
                    if (!StringUtils.hasText(msg)) {
                        String body = fm.group(2) == null ? "" : fm.group(2).trim();
                        msg = body.lines().findFirst().orElse("failed");
                    }
                    msg = msg.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
                    if (msg.length() > 240) {
                        msg = msg.substring(0, 240) + "…";
                    }
                    lines.add("- " + className + "#" + method + "：" + msg);
                }
                if (lines.size() >= 12) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("junit scan skipped: {}", e.getMessage());
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "失败用例（来自 JUnit XML）：\n" + String.join("\n", lines);
    }

    private static String trimBlock(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max / 2) + "\n...\n" + t.substring(t.length() - max / 2);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
