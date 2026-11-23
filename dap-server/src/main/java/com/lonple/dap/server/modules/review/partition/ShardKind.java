package com.lonple.dap.server.modules.review.partition;

/**
 * 评审分片类型（确定性分类，非 LLM 路由）。
 */
public enum ShardKind {
    FRONTEND,
    BACKEND,
    MYBATIS_XML,
    SQL,
    CONFIG,
    /** MVP：跳过，不启 Worker */
    OTHER
}
