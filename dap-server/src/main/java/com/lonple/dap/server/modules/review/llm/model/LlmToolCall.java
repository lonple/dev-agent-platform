package com.lonple.dap.server.modules.review.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmToolCall {
    private String id;
    private String name;
    /** 模型给出的 JSON 参数字符串 */
    private String arguments;
}
