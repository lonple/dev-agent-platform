package com.lonple.dap.server.modules.review.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class MemorySearchClient {
    private static final Logger log = LoggerFactory.getLogger(MemorySearchClient.class);

    private final RestClient client;

    public MemorySearchClient(@Value("${dap.review.knowledge.base-url:http://127.0.0.1:8010}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(60));
        String url = baseUrl == null ? "http://127.0.0.1:8010" : baseUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 直连 ada-memory {@code GET /api/search}（与 MCP search_knowledge 同源）。
     * 需任务配置 productName；无命中时 hitCount=0。
     */
    public KnowledgeSearchResult search(String query, String productName, int topK) {
        long t0 = System.currentTimeMillis();
        String q = query == null ? "" : query.trim();
        String product = productName == null ? "" : productName.trim();
        if (q.isEmpty()) {
            return new KnowledgeSearchResult(0, "（检索问句为空，未调用 memory）");
        }
        if (product.isEmpty()) {
            return new KnowledgeSearchResult(0, "（未配置产品名称 productName，无法检索业务知识）");
        }
        try {
            JsonNode body = client.get()
                    .uri(uri -> uri.path("/api/search")
                            .queryParam("q", q)
                            .queryParam("categoryCode", "GENERAL")
                            .queryParam("topK", topK)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = body != null && body.has("data") ? body.get("data") : body;
            JsonNode units = data != null && data.has("units") ? data.get("units") : null;
            int count = units != null && units.isArray() ? units.size() : 0;
            log.info("[memory-search] ok costMs={} product={} count={} q={}",
                    System.currentTimeMillis() - t0, product, count, abbrev(q, 80));
            if (count == 0) {
                return new KnowledgeSearchResult(0, "（未检索到业务知识；product=" + product + "）");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("检索命中 ").append(count).append(" 条（product=").append(product).append("）：\n");
            int i = 0;
            int budget = 6_000;
            boolean truncated = false;
            for (JsonNode u : units) {
                if (i++ >= topK) {
                    break;
                }
                String line = "- id=" + text(u, "id")
                        + " kind=" + text(u, "kind")
                        + " title=" + text(u, "title") + "\n"
                        + "  summary=" + abbrev(text(u, "summary"), 300) + "\n";
                if (sb.length() + line.length() > budget) {
                    truncated = true;
                    break;
                }
                sb.append(line);
            }
            if (truncated) {
                sb.append("...[知识注入已达字符预算]\n");
            }
            return new KnowledgeSearchResult(count, sb.toString());
        } catch (Exception e) {
            log.warn("[memory-search] failed: {}", e.getMessage());
            return new KnowledgeSearchResult(0, "（知识检索失败: " + e.getMessage() + "）");
        }
    }

    public record KnowledgeSearchResult(int hitCount, String summary) {
        public boolean empty() {
            return hitCount <= 0;
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return "";
        }
        return n.get(field).asText("");
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
