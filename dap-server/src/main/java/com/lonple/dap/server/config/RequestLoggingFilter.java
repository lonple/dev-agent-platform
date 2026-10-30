package com.lonple.dap.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final int BODY_LIMIT = 2000;
    private static final Set<String> SKIP_PREFIX = Set.of("/actuator");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (SKIP_PREFIX.stream().anyMatch(path::startsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        response.setHeader("X-Request-Id", requestId);

        // SSE / 异步长连接绝不能包 ContentCachingResponseWrapper，否则会缓冲整段响应，
        // 前端看不到打字机增量与 step 事件（只在结束后或刷新才有内容）。
        if (isStreamingPath(path, request)) {
            long start = System.currentTimeMillis();
            try {
                filterChain.doFilter(request, response);
            } finally {
                log.info(
                        "HTTP {} {} status={} cost={}ms requestId={} tenant={} body=-",
                        request.getMethod(),
                        path + (request.getQueryString() == null ? "" : "?" + request.getQueryString()),
                        response.getStatus(),
                        System.currentTimeMillis() - start,
                        requestId,
                        request.getHeader("X-Tenant-Id") == null ? "-" : request.getHeader("X-Tenant-Id"));
            }
            return;
        }

        boolean cacheBody = !"GET".equalsIgnoreCase(request.getMethod())
                && !"HEAD".equalsIgnoreCase(request.getMethod())
                && isJsonLike(request.getContentType());

        long start = System.currentTimeMillis();
        ContentCachingRequestWrapper reqWrapper =
                cacheBody ? new ContentCachingRequestWrapper(request, BODY_LIMIT) : null;
        ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(response);

        HttpServletRequest effectiveReq = reqWrapper != null ? reqWrapper : request;
        try {
            filterChain.doFilter(effectiveReq, respWrapper);
        } finally {
            long cost = System.currentTimeMillis() - start;
            String tenant = request.getHeader("X-Tenant-Id");
            String body = "";
            if (reqWrapper != null) {
                // JSON 一律按 UTF-8 解码，避免 CharacterEncoding 未设时按系统默认码页导致日志乱码
                body = summarizeBody(path, reqWrapper.getContentAsByteArray());
            }
            log.info(
                    "HTTP {} {} status={} cost={}ms requestId={} tenant={} body={}",
                    request.getMethod(),
                    path + (request.getQueryString() == null ? "" : "?" + request.getQueryString()),
                    respWrapper.getStatus(),
                    cost,
                    requestId,
                    tenant == null ? "-" : tenant,
                    body.isEmpty() ? "-" : body);
            respWrapper.copyBodyToResponse();
        }
    }

    private static boolean isStreamingPath(String path, HttpServletRequest request) {
        if (path != null && path.contains("/chat/completions")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase().contains("text/event-stream");
    }

    private static boolean isJsonLike(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return ct.contains("json") || ct.contains("text/plain");
    }

    /** write 类接口正文很长，日志只保留文件名等摘要，避免控制台刷屏/乱码误导 */
    private static String summarizeBody(String path, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith("/write") || lower.contains("/write?")) {
            String name = extractJsonStringField(text, "displayName");
            return "displayName=" + (name == null ? "?" : name) + ", contentChars≈" + estimateContentChars(text);
        }
        return truncate(text);
    }

    private static String extractJsonStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }

    private static int estimateContentChars(String json) {
        String key = "\"content\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return 0;
        }
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) {
            return 0;
        }
        return Math.max(0, json.length() - colon - 1);
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= BODY_LIMIT) {
            return oneLine;
        }
        return oneLine.substring(0, BODY_LIMIT) + "...";
    }
}
