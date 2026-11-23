package com.lonple.dap.server.modules.pipeline;

/**
 * 集成测试整轮结论。只认报告「结论」行，不认单条用例里的通过/失败字样。
 */
public enum IntTestVerdict {
    PASSED,
    FAILED,
    /** 无法从正文解析结论时：不触发修复闭环，走原成功/待发布路径 */
    UNKNOWN
}
