package com.lonple.dap.server.modules.agenttool.repo;

import com.lonple.dap.server.common.BizException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写入内容辅助扫描：拦截明显的仓外绝对路径与路径穿越片段。
 * 仅为增强，不能替代路径闸门。
 */
public final class CodeContentGuard {

    private static final Pattern WIN_ABS = Pattern.compile("(?i)(?:^|[\"'\\s(=])([A-Z]:\\\\(?:[^\\s\"']+))");
    private static final Pattern UNIX_ABS = Pattern.compile("(?:^|[\"'\\s(=])(/(?:etc|usr|var|home|root|tmp)/[^\\s\"']+)");
    private static final Pattern TRAVERSAL = Pattern.compile("(^|[/\\\\])\\.\\.(/|\\\\|$)");

    private static final List<String> SENSITIVE_REL = List.of(
            ".git/hooks",
            ".git/config",
            ".ssh/",
            "id_rsa",
            "id_ed25519",
            ".env");

    private CodeContentGuard() {}

    public static void assertWritableRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BizException("写入路径为空");
        }
        String rel = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE_REL) {
            if (rel.contains(s.toLowerCase(Locale.ROOT))) {
                throw new BizException("禁止写入敏感路径：" + relativePath);
            }
        }
    }

    public static void assertContentSafe(String content, PathContext ctx) {
        if (!StringUtils.hasText(content) || content.length() > 2_000_000) {
            if (content != null && content.length() > 2_000_000) {
                throw new BizException("单次写入内容过大");
            }
            return;
        }
        List<String> hits = new ArrayList<>();
        if (TRAVERSAL.matcher(content).find()) {
            hits.add("包含路径穿越片段(..)");
        }
        Matcher win = WIN_ABS.matcher(content);
        while (win.find()) {
            String p = win.group(1);
            if (ctx == null || !ctx.isUnderAnyRepo(p)) {
                hits.add("疑似仓外 Windows 绝对路径: " + truncate(p));
                break;
            }
        }
        Matcher unix = UNIX_ABS.matcher(content);
        while (unix.find()) {
            String p = unix.group(1);
            if (ctx == null || !ctx.isUnderAnyRepo(p)) {
                hits.add("疑似仓外 Unix 绝对路径: " + truncate(p));
                break;
            }
        }
        if (!hits.isEmpty()) {
            throw new BizException("写入内容未通过安全检查：" + String.join("；", hits));
        }
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    public interface PathContext {
        boolean isUnderAnyRepo(String absolutePathHint);
    }
}
