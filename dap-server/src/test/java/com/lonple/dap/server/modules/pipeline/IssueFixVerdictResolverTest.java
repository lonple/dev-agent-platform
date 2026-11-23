package com.lonple.dap.server.modules.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IssueFixVerdictResolverTest {

    @Test
    void fixedConclusion() {
        assertEquals(IssueFixVerdict.FIXED,
                IssueFixVerdictResolver.resolve("改完了。\n\n**结论：已修复**\n"));
    }

    @Test
    void incompleteConclusion() {
        assertEquals(IssueFixVerdict.INCOMPLETE,
                IssueFixVerdictResolver.resolve("**结论：未完成**\n还缺 Controller"));
    }

    @Test
    void session9149StyleIncompleteWithoutConclusion() {
        String reply = """
                已提交报告。LoanController 还没有创建，因此在当前环境下复测仍会返回 404。
                报告中 §5 已列出 6 项遗留工作。
                """;
        assertEquals(IssueFixVerdict.INCOMPLETE, IssueFixVerdictResolver.resolve(reply));
    }

    @Test
    void unknownClean() {
        assertEquals(IssueFixVerdict.UNKNOWN,
                IssueFixVerdictResolver.resolve("已按确认修改并写出报告。"));
    }
}
