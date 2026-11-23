package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

@Data
public class PipelineStartRequest {
    /** 需求流水：与 issueId 二选一 */
    private Long requirementId;
    /** 问题单流水：与 requirementId 二选一 */
    private Long issueId;
    /**
     * 可选；需求默认 RD_AUTO，问题单默认 ISSUE_AUTO。
     */
    private String definitionCode;
}
