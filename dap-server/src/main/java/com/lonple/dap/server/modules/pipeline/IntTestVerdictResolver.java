package com.lonple.dap.server.modules.pipeline;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 回复 / 集成测试报告中解析整轮通过与否。
 * <p>
 * 旧逻辑用 {@code contains("**通过**")}，会被「TC-xxx → **通过**」误伤，导致结论「不通过」
 * 仍不进修复闭环（见 session #9143）。
 */
public final class IntTestVerdictResolver {

    /**
     * 匹配「结论」附近的通过/不通过，例如：
     * <ul>
     *   <li>{@code **结论：不通过**}</li>
     *   <li>{@code 结论：**通过**}</li>
     *   <li>{@code 结论: 不通过}</li>
     * </ul>
     */
    private static final Pattern CONCLUSION = Pattern.compile(
            "(?i)(?:\\*{0,2}\\s*)?结论\\s*[:：]\\s*(?:\\*{0,2}\\s*)?(不通过|通过)");

    private IntTestVerdictResolver() {
    }

    public static IntTestVerdict resolve(String reply) {
        if (reply == null || reply.isBlank()) {
            return IntTestVerdict.UNKNOWN;
        }
        Matcher m = CONCLUSION.matcher(reply);
        IntTestVerdict last = null;
        while (m.find()) {
            String word = m.group(1);
            if (word != null && word.contains("不")) {
                last = IntTestVerdict.FAILED;
            } else {
                last = IntTestVerdict.PASSED;
            }
        }
        if (last != null) {
            return last;
        }
        String lower = reply.toLowerCase(Locale.ROOT);
        if (lower.contains("\"result\":\"fail\"") || lower.contains("\"result\": \"fail\"")) {
            return IntTestVerdict.FAILED;
        }
        if (lower.contains("\"result\":\"pass\"") || lower.contains("\"result\": \"pass\"")) {
            return IntTestVerdict.PASSED;
        }
        return IntTestVerdict.UNKNOWN;
    }
}
