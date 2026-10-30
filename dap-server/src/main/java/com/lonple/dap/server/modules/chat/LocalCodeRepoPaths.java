package com.lonple.dap.server.modules.chat;

import com.lonple.dap.server.common.BizException;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 本地代码仓库路径规范化与校验（当前版本仅支持本机绝对路径）。 */
public final class LocalCodeRepoPaths {

    private LocalCodeRepoPaths() {}

    public static List<String> normalizeAndValidate(List<String> rawPaths, boolean required) {
        List<String> cleaned = new ArrayList<>();
        if (rawPaths != null) {
            for (String raw : rawPaths) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                cleaned.add(normalizeOne(raw.trim()));
            }
        }
        // 去重保序
        Set<String> seen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        for (String p : cleaned) {
            if (seen.add(p)) {
                unique.add(p);
            }
        }
        if (required && unique.isEmpty()) {
            throw new BizException("编码任务请至少填写一个本地代码仓库地址");
        }
        if (unique.size() > 20) {
            throw new BizException("代码仓库最多 20 个");
        }
        return unique;
    }

    private static String normalizeOne(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("git@")
                || lower.startsWith("ssh://")) {
            throw new BizException("当前版本仅支持本地代码仓库地址，不支持远程 URL：" + path);
        }
        Path p;
        try {
            p = Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new BizException("代码仓库路径无效：" + path);
        }
        if (!p.isAbsolute()) {
            throw new BizException("代码仓库须为本地绝对路径：" + path);
        }
        if (!Files.exists(p)) {
            throw new BizException("代码仓库路径不存在：" + p);
        }
        if (!Files.isDirectory(p)) {
            throw new BizException("代码仓库须为本地目录：" + p);
        }
        return p.toString();
    }
}
