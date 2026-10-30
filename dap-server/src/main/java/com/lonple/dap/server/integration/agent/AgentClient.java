package com.lonple.dap.server.integration.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lonple.dap.server.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentClient {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public void streamChat(Map<String, Object> body, BiConsumer<String, String> onEvent, Runnable onComplete)
            throws Exception {
        String json = objectMapper.writeValueAsString(body);
        String url = trimSlash(properties.getBaseUrl()) + "/v1/chat/stream";
        log.info("agent stream POST {} bodyChars={}", url, json.length());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Internal-Token", properties.getInternalToken())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        log.info("agent stream response status={}", response.statusCode());
        if (response.statusCode() >= 400) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            log.error("agent stream failed status={} body={}", response.statusCode(), err);
            throw new BizException(502, "Agent 调用失败: " + err);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        onEvent.accept(event, data.toString());
                        data.setLength(0);
                        event = "message";
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            if (!data.isEmpty()) {
                onEvent.accept(event, data.toString());
            }
        }
        if (onComplete != null) {
            onComplete.run();
        }
    }

    public String generateTitle(String question) {
        try {
            Map<String, Object> body = Map.of("question", question == null ? "" : question);
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(properties.getBaseUrl()) + "/v1/chat/title"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return null;
            }
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode title = node.get("title");
            return title == null || title.isNull() ? null : title.asText();
        } catch (Exception e) {
            log.warn("generate title failed: {}", e.getMessage());
            return null;
        }
    }

    public void stop(String runId) {
        try {
            Map<String, Object> body = Map.of("run_id", runId);
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(properties.getBaseUrl()) + "/v1/chat/stop"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("stop agent run failed: {}", e.getMessage());
        }
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
