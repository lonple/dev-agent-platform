package com.lonple.dap.server.modules.review.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lonple.dap.server.modules.review.llm.config.LlmProperties;
import com.lonple.dap.server.modules.review.llm.error.LlmException;
import com.lonple.dap.server.modules.review.llm.model.ChatMessage;
import com.lonple.dap.server.modules.review.llm.model.ChatRequest;
import com.lonple.dap.server.modules.review.llm.model.ChatResponse;
import com.lonple.dap.server.modules.review.llm.model.RerankHit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@EnableConfigurationProperties(LlmProperties.class)
public class HttpLlmClient implements LlmClient {
    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public HttpLlmClient(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 与 dap-agent 共用 LLM_API_KEY；兼容旧 ADA_LLM_API_KEY / DAP_REVIEW_LLM_API_KEY。 */
    private String resolveApiKey() {
        return firstNonBlank(
                properties.getApiKey(),
                System.getenv("DAP_REVIEW_LLM_API_KEY"),
                System.getenv("LLM_API_KEY"),
                System.getenv("ADA_LLM_API_KEY"));
    }

    private String resolveBaseUrl() {
        return firstNonBlank(
                properties.getBaseUrl(),
                System.getenv("DAP_REVIEW_LLM_BASE_URL"),
                System.getenv("LLM_BASE_URL"),
                "https://dashscope.aliyuncs.com/compatible-mode");
    }

    private String resolveChatModel() {
        return firstNonBlank(
                properties.getChatModel(),
                System.getenv("DAP_REVIEW_LLM_CHAT_MODEL"),
                System.getenv("LLM_MODEL"),
                "qwen-max");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String key = resolveApiKey();
        if (!properties.isEnabled() || key.isEmpty()) {
            throw new LlmException("评审模型未配置：请在 dap-agent/.env 设置 LLM_API_KEY（与 agent 共用）");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            String model = request.getModel() == null || request.getModel().isBlank()
                    ? resolveChatModel() : request.getModel();
            body.put("model", model);
            if (request.getTemperature() != null) {
                body.put("temperature", request.getTemperature());
            }
            if ("json_object".equals(request.getResponseFormat())) {
                body.putObject("response_format").put("type", "json_object");
            }
            ArrayNode messages = body.putArray("messages");
            for (ChatMessage message : request.getMessages() == null ? List.<ChatMessage>of() : request.getMessages()) {
                ObjectNode m = messages.addObject();
                m.put("role", message.getRole() == null ? "user" : message.getRole());
                m.put("content", message.getContent() == null ? "" : message.getContent());
            }
            String base = resolveBaseUrl();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (base.endsWith("/v1")) {
                base = base.substring(0, base.length() - 3);
            }
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new LlmException("模型调用失败 HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return ChatResponse.builder().content(content).model(model).build();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("模型调用失败: " + e.getMessage());
        }
    }

    @Override
    public float[] embed(String text) {
        return new float[0];
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (texts != null) {
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[0]);
            }
        }
        return out;
    }

    @Override
    public List<RerankHit> rerank(String query, List<String> documents) {
        return List.of();
    }
}
