package com.lonple.dap.server.modules.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntTestVerdictResolverTest {

    @Test
    void conclusionFailNotFooledByCasePass() {
        String reply = """
                已完成本问题的集成测试执行与报告输出。**结论：不通过**。
                - TC-ISSUE3-001 → **404 失败**
                - TC-ISSUE3-004 `LoanBorrowRequest` 归一化/校验 → **通过**
                2. **全量回归**：基线 117 例全部通过。
                """;
        assertEquals(IntTestVerdict.FAILED, IntTestVerdictResolver.resolve(reply));
    }

    @Test
    void conclusionPass() {
        assertEquals(IntTestVerdict.PASSED,
                IntTestVerdictResolver.resolve("报告完成。\n\n**结论：通过**\n"));
    }

    @Test
    void bareCasePassIsUnknown() {
        assertEquals(IntTestVerdict.UNKNOWN,
                IntTestVerdictResolver.resolve("TC-001 → **通过**\nTC-002 → **失败**"));
    }

    @Test
    void jsonResultFail() {
        assertEquals(IntTestVerdict.FAILED,
                IntTestVerdictResolver.resolve("{\"result\":\"fail\",\"cases\":[]}"));
    }
}
