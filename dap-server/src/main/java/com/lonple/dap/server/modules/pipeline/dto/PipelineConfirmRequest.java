package com.lonple.dap.server.modules.pipeline.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class PipelineConfirmRequest {
    /** findings 处置：id -> { agree: true/false, comment } */
    private List<Map<String, Object>> findingAnswers = new ArrayList<>();
    /** openQuestions：id -> answer */
    private List<Map<String, Object>> questionAnswers = new ArrayList<>();
}
