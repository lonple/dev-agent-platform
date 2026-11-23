package com.lonple.dap.server.modules.pipeline;

/** 用户主动停止单元 Agent 执行（不可重试）。 */
public class PipeUnitStoppedException extends RuntimeException {
    public PipeUnitStoppedException() {
        super("用户已停止");
    }
}
