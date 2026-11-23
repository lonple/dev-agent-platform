package com.lonple.dap.server.modules.review.llm.client;

import com.lonple.dap.server.modules.review.llm.model.ChatRequest;
import com.lonple.dap.server.modules.review.llm.model.ChatResponse;
import com.lonple.dap.server.modules.review.llm.model.RerankHit;

import java.util.List;

/** Pure model access — no business semantics. */
public interface LlmClient {
    ChatResponse chat(ChatRequest request);

    float[] embed(String text);

    List<float[]> embed(List<String> texts);

    /**
     * Cross-encoder rerank. Returns hits sorted by relevance descending;
     * {@link RerankHit#index()} refers to the input {@code documents} list.
     */
    List<RerankHit> rerank(String query, List<String> documents);
}
