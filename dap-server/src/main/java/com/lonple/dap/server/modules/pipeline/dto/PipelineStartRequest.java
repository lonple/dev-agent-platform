package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

@Data
public class PipelineStartRequest {
    /** 可选：从指定步骤开始，默认定义中 sort_order 最小的人闸/步骤 */
    private String fromStepCode;
}
