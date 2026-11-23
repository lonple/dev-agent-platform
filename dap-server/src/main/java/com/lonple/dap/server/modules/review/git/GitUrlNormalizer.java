package com.lonple.dap.server.modules.review.git;

import com.lonple.dap.server.common.BizException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class GitUrlNormalizer {
    private GitUrlNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("remoteUrl 不能为空");
        }
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // strip embedded credentials for key
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                URI uri = URI.create(url);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                int port = uri.getPort();
                String path = uri.getPath() == null ? "" : uri.getPath();
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if (path.endsWith(".git")) {
                    path = path.substring(0, path.length() - 4);
                }
                StringBuilder sb = new StringBuilder();
                sb.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://").append(host);
                if (port > 0) {
                    sb.append(':').append(port);
                }
                sb.append(path);
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        // ssh style git@host:path
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".git")) {
            lower = lower.substring(0, lower.length() - 4);
        }
        return lower;
    }

    public static String toCodeKey(String rawUrl) {
        String n = normalize(rawUrl);
        String safe = n.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 60) + "_" + shortHash(n);
        }
        return safe;
    }

    private static String shortHash(String s) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 10);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
