package com.lonple.dap.server.modules.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从问题修复报告/助手回复解析是否真正修完。
 * <p>
 * #9149：只写了 Service 骨架 +《问题修复报告.md》，正文自述仍 404 / 有遗留，
 * 平台却因有正式文件名进了待发布。
 */
public final class IssueFixVerdictResolver {

    private static final Pattern CONCLUSION = Pattern.compile(
            "(?i)(?:\\*{0,2}\\s*)?结论\\s*[:：]\\s*(?:\\*{0,2}\\s*)?(已修复|修复完成|未完成|未修复|部分完成)");

    private IssueFixVerdictResolver() {
    }

    public static IssueFixVerdict resolve(String text) {
        if (text == null || text.isBlank()) {
            return IssueFixVerdict.UNKNOWN;
        }
        Matcher m = CONCLUSION.matcher(text);
        IssueFixVerdict last = null;
        while (m.find()) {
            String word = m.group(1);
            if (word == null) {
                continue;
            }
            if (word.contains("未") || word.contains("部分")) {
                last = IssueFixVerdict.INCOMPLETE;
            } else {
                last = IssueFixVerdict.FIXED;
            }
        }
        if (last != null) {
            return last;
        }
        // 无结论行时：正文自认未修完 → 未完成（避免再出现只交报告就结束）
        if (looksIncomplete(text)) {
            return IssueFixVerdict.INCOMPLETE;
        }
        return IssueFixVerdict.UNKNOWN;
    }

    private static boolean looksIncomplete(String text) {
        String[] markers = {
                "仍会返回 404",
                "仍会返回404",
                "还没有创建",
                "尚未创建",
                "未创建 Controller",
                "未创建Controller",
                "遗留工作",
                "本轮交付窗口受限",
                "只实现到服务层骨架",
                "会影响缺陷修复是否完成",
                "复测仍会",
                "必须提醒的风险"
        };
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
