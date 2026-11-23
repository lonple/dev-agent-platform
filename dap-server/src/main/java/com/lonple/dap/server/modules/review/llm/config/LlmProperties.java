package com.lonple.dap.server.modules.review.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "dap.review.model")
public class LlmProperties {
    /**
     * 是否启用真实大模型（OpenAI 兼容协议）。
     * false 或缺少 api-key 时 chat 拒绝，embed 使用本地 stub。
     * 与 dap-agent 共用 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL。
     */
    private boolean enabled = false;
    /** API Key；优先 DAP_REVIEW_LLM_API_KEY，否则 LLM_API_KEY（与 agent 同一把） */
    private String apiKey = "";
    /**
     * OpenAI 兼容 base（可带 /v1，客户端会剥掉再拼 /v1/chat/completions）。
     * 与 agent 的 LLM_BASE_URL 对齐。
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
    private String chatModel = "qwen-max";
    /**
     * 知识切片专用模型。默认 Qwen3b-RagSemanticChunker（Qwen2.5-3B 语义切片微调），
     * 输出以「段」分隔。查询改写等仍用 chatModel。
     */
    private String sliceModel = "Qwen3b-RagSemanticChunker";
    /** 是否走远程 embedding；false 时用本地 stub */
    private boolean embedEnabled = false;
    private String embedBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
    private String embedApiKey = "";
    private String embedModel = "text-embedding-v3";
    /** text-embedding-v3 常用 1024（亦可 768/512） */
    private int embedDim = 1024;
    /**
     * 是否调用千问文本重排（DashScope text-rerank，非 OpenAI 兼容协议）。
     * false 或缺少 key 时检索保持加权融合顺序。
     */
    private boolean rerankEnabled = true;
    /**
     * 重排 HTTP 完整地址。默认北京地域 DashScope text-rerank。
     */
    private String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private String rerankApiKey = "";
    /** 默认 qwen3-rerank；也可改为 gte-rerank-v2 */
    private String rerankModel = "qwen3-rerank";
    private long timeoutMs = 60_000;
    private int maxRetries = 2;
}
