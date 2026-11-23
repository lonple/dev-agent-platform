package com.lonple.dap.server.modules.pipeline;

public final class PipeJobTypes {
    public static final String RUN_UNIT = "RUN_UNIT";
    public static final String CONTINUE_UNIT = "CONTINUE_UNIT";
    public static final String ADVANCE = "ADVANCE";
    public static final String LLM_MERGE = "LLM_MERGE";
    public static final String INT_TEST_RUN = "INT_TEST_RUN";
    public static final String INT_TEST_FIX = "INT_TEST_FIX";
    /** 代码评审确认后，只改用户点了「修改」的项 */
    public static final String CODE_REVIEW_FIX = "CODE_REVIEW_FIX";

    /** 编码阶段：任务拆分 */
    public static final String CODING_PLAN = "CODING_PLAN";
    /** 编码阶段：串/并行分析（可无 LLM） */
    public static final String CODING_ANALYZE = "CODING_ANALYZE";
    /** 编码阶段：单任务卡实现 */
    public static final String CODING_TASK = "CODING_TASK";
    /** 编码阶段：汇总验收与编码完成报告 */
    public static final String CODING_INTEGRATE = "CODING_INTEGRATE";

    private PipeJobTypes() {}

    public static boolean isCodingStage(String jobType) {
        return CODING_PLAN.equals(jobType)
                || CODING_ANALYZE.equals(jobType)
                || CODING_TASK.equals(jobType)
                || CODING_INTEGRATE.equals(jobType);
    }

    /** 中间阶段：不要求「编码完成报告.md」 */
    public static boolean isCodingIntermediate(String jobType) {
        return CODING_PLAN.equals(jobType)
                || CODING_ANALYZE.equals(jobType)
                || CODING_TASK.equals(jobType);
    }
}
