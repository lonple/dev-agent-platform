package com.lonple.dap.server.modules.agenttool;

import com.lonple.dap.server.config.RipgrepProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 通过本机 ripgrep 搜索指定文件列表；不可用时由调用方回退。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RipgrepSearchService {

    private final RipgrepProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Boolean> availableCache = new AtomicReference<>();
    private final AtomicBoolean probing = new AtomicBoolean(false);

    public boolean isAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }
        Boolean cached = availableCache.get();
        if (cached != null) {
            return cached;
        }
        if (!probing.compareAndSet(false, true)) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(properties.getExecutable(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            boolean ok = finished && p.exitValue() == 0;
            availableCache.set(ok);
            if (!ok) {
                log.info("ripgrep unavailable: executable={}", properties.getExecutable());
            } else {
                log.info("ripgrep available: {}", properties.getExecutable());
            }
            return ok;
        } catch (Exception e) {
            availableCache.set(false);
            log.info("ripgrep not found: {}", e.getMessage());
            return false;
        } finally {
            probing.set(false);
        }
    }

    /**
     * @return null 表示执行失败，调用方应回退；空列表表示无命中
     */
    public List<Hit> search(String query, List<Path> files) {
        if (!StringUtils.hasText(query) || files == null || files.isEmpty()) {
            return List.of();
        }
        List<Path> existing = new ArrayList<>();
        for (Path f : files) {
            if (f != null && Files.isRegularFile(f)) {
                existing.add(f.toAbsolutePath().normalize());
            }
        }
        if (existing.isEmpty()) {
            return List.of();
        }

        Path listFile = null;
        try {
            listFile = Files.createTempFile("dap-rg-files-", ".txt");
            StringBuilder sb = new StringBuilder();
            for (Path f : existing) {
                sb.append(f).append('\n');
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

            List<String> cmd = new ArrayList<>();
            cmd.add(properties.getExecutable());
            cmd.add("--json");
            cmd.add("-i");
            cmd.add("-C");
            cmd.add("1");
            cmd.add("--max-count");
            cmd.add(String.valueOf(Math.max(1, properties.getMaxCountPerFile())));
            cmd.add("--max-filesize");
            cmd.add("512K");
            // 多词：任一命中（与行扫描 OR 语义一致）
            String pattern = buildPattern(query);
            if (pattern.startsWith("regex:")) {
                cmd.add(pattern.substring("regex:".length()));
            } else {
                cmd.add("-F");
                cmd.add(pattern);
            }
            cmd.add("--files-from");
            cmd.add(listFile.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<Hit> hits = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                // 收集 context 行，拼接到随后的 match
                Map<String, List<String>> pendingContext = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (hits.size() >= properties.getMaxHits()) {
                        break;
                    }
                    parseJsonLine(line, hits, pendingContext);
                }
            }

            boolean finished = process.waitFor(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ripgrep timeout after {}ms", properties.getTimeoutMs());
                return null;
            }
            int code = process.exitValue();
            // 0=命中 1=无命中 2=错误
            if (code == 0 || code == 1) {
                return hits;
            }
            log.warn("ripgrep exit code={}", code);
            return null;
        } catch (Exception e) {
            log.warn("ripgrep search failed: {}", e.getMessage());
            availableCache.set(false);
            return null;
        } finally {
            if (listFile != null) {
                try {
                    Files.deleteIfExists(listFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void parseJsonLine(String line, List<Hit> hits, Map<String, List<String>> pendingContext) {
        try {
            JsonNode root = objectMapper.readTree(line);
            String type = root.path("type").asText("");
            JsonNode data = root.path("data");
            String path = data.path("path").path("text").asText("");
            if (!StringUtils.hasText(path)) {
                return;
            }
            if ("context".equals(type)) {
                int ln = data.path("line_number").asInt(0);
                String text = data.path("lines").path("text").asText("");
                if (text.endsWith("\n")) {
                    text = text.substring(0, text.length() - 1);
                }
                pendingContext.computeIfAbsent(path, k -> new ArrayList<>()).add(ln + "|" + text);
                return;
            }
            if (!"match".equals(type)) {
                return;
            }
            int ln = data.path("line_number").asInt(0);
            String text = data.path("lines").path("text").asText("");
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            List<String> ctx = pendingContext.remove(path);
            StringBuilder snip = new StringBuilder();
            if (ctx != null) {
                for (String c : ctx) {
                    if (snip.length() > 0) {
                        snip.append('\n');
                    }
                    snip.append(c);
                }
            }
            if (snip.length() > 0) {
                snip.append('\n');
            }
            snip.append(ln).append('|').append(text);
            Hit hit = new Hit();
            hit.path = Path.of(path).toAbsolutePath().normalize();
            hit.line = ln;
            hit.snippet = snip.toString();
            hits.add(hit);
        } catch (Exception ignored) {
            // skip malformed
        }
    }

    /** 单词固定串；多词转成正则 OR */
    private String buildPattern(String query) {
        String q = query.trim();
        String[] parts = q.split("\\s+");
        List<String> kws = new ArrayList<>();
        for (String p : parts) {
            if (StringUtils.hasText(p)) {
                kws.add(p);
            }
        }
        if (kws.isEmpty()) {
            return q;
        }
        if (kws.size() == 1) {
            return kws.get(0);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < kws.size(); i++) {
            if (i > 0) {
                regex.append('|');
            }
            regex.append(Pattern.quote(kws.get(i)));
        }
        return "regex:" + regex;
    }

    public static final class Hit {
        public Path path;
        public int line;
        public String snippet;
    }
}
