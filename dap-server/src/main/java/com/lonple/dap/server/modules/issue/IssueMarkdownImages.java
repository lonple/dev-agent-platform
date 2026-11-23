package com.lonple.dap.server.modules.issue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题单 Markdown 中的附件图：按出现顺序编号为【附图N】，并转为 LLM 多模态 data URL。
 * <p>
 * 关联方式：文中把 {@code ![alt](/api/issues/attachments/x)} 换成 {@code 【附图N｜alt】}，
 * 同时 images 列表第 N 项带 {@code label=附图N}，与文中占位一一对应。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueMarkdownImages {

    /** 匹配 Markdown 图片；group1=alt，group2=fileName */
    private static final Pattern MD_IMAGE = Pattern.compile(
            "!\\[([^\\]]*)]\\(/api/issues/attachments/([A-Za-z0-9._-]+)\\)");
    private static final Pattern EXISTING_MARKER = Pattern.compile("【附图\\d+(?:｜[^】]*)?】\\s*");
    private static final int MAX_IMAGES = 12;

    private final IssueAttachmentService attachmentService;

    public record Prepared(
            /** 供 LLM 阅读的正文：图片位置已换成【附图N｜alt】 */
            String rewrittenText,
            /** 每项含 label / url / alt，顺序与文中编号一致 */
            List<Map<String, String>> images
    ) {}

    /**
     * 合并多段 Markdown，按全文出现顺序编号并加载图片。
     */
    public Prepared prepareForLlm(String... markdownParts) {
        StringBuilder raw = new StringBuilder();
        if (markdownParts != null) {
            for (String part : markdownParts) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                if (!raw.isEmpty()) {
                    raw.append("\n\n");
                }
                raw.append(part.trim());
            }
        }
        return prepareOne(raw.toString());
    }

    /**
     * 写入 Wiki「问题单.md」时：在原图片 Markdown 前插入【附图N】标记（保留原图供人预览）。
     * {@code nextIndex} 跨章节连续编号（从 1 起）。
     */
    public String annotateForWiki(String markdown, int[] nextIndex) {
        if (!StringUtils.hasText(markdown)) {
            return markdown == null ? "" : markdown;
        }
        if (nextIndex == null || nextIndex.length < 1) {
            nextIndex = new int[]{1};
        }
        String cleaned = EXISTING_MARKER.matcher(markdown).replaceAll("");
        StringBuffer sb = new StringBuffer();
        Matcher m = MD_IMAGE.matcher(cleaned);
        while (m.find()) {
            int n = nextIndex[0];
            if (n > MAX_IMAGES) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            nextIndex[0] = n + 1;
            String alt = m.group(1) == null ? "" : m.group(1).trim();
            String marker = StringUtils.hasText(alt)
                    ? "【附图" + n + "｜" + alt + "】"
                    : "【附图" + n + "】";
            m.appendReplacement(sb, Matcher.quoteReplacement(marker + "\n" + m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Prepared prepareOne(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return new Prepared("", List.of());
        }
        // 先去掉旧标记，避免重复编号
        String cleaned = EXISTING_MARKER.matcher(markdown).replaceAll("");

        // 按出现顺序编号（同一文件出现多次也各占一号，与文中位置一一对应）
        List<ImgRef> refs = new ArrayList<>();
        StringBuffer rewritten = new StringBuffer();
        Matcher replaceMd = MD_IMAGE.matcher(cleaned);
        while (replaceMd.find()) {
            String file = replaceMd.group(2);
            String alt = replaceMd.group(1) == null ? "" : replaceMd.group(1).trim();
            String marker;
            if (refs.size() >= MAX_IMAGES) {
                marker = "【附图·超出上限已省略】";
            } else {
                ImgRef ref = new ImgRef(refs.size() + 1, file, alt);
                refs.add(ref);
                marker = StringUtils.hasText(alt)
                        ? "【附图" + ref.index + "｜" + alt + "】"
                        : "【附图" + ref.index + "】";
            }
            replaceMd.appendReplacement(rewritten, Matcher.quoteReplacement(marker));
        }
        replaceMd.appendTail(rewritten);

        // 尚未被 MD 语法覆盖的裸 URL
        StringBuffer bareOut = new StringBuffer();
        Matcher bareReplace = Pattern.compile("/api/issues/attachments/([A-Za-z0-9._-]+)").matcher(rewritten.toString());
        while (bareReplace.find()) {
            String file = bareReplace.group(1);
            String marker;
            if (refs.size() >= MAX_IMAGES) {
                marker = "【附图·超出上限已省略】";
            } else {
                ImgRef ref = new ImgRef(refs.size() + 1, file, "");
                refs.add(ref);
                marker = "【附图" + ref.index + "】";
            }
            bareReplace.appendReplacement(bareOut, Matcher.quoteReplacement(marker));
        }
        bareReplace.appendTail(bareOut);
        String text = bareOut.toString();

        List<Map<String, String>> images = new ArrayList<>();
        for (ImgRef ref : refs) {
            try {
                IssueAttachmentService.LoadedFile loaded = attachmentService.load(ref.fileName);
                String mime = mediaTypeValue(loaded.mediaType());
                String b64 = Base64.getEncoder().encodeToString(loaded.bytes());
                Map<String, String> part = new HashMap<>();
                part.put("label", "附图" + ref.index);
                part.put("alt", ref.alt == null ? "" : ref.alt);
                part.put("url", "data:" + mime + ";base64," + b64);
                images.add(part);
            } catch (Exception ex) {
                log.warn("skip issue attachment {} (附图{}): {}", ref.fileName, ref.index, ex.getMessage());
            }
        }
        if (!images.isEmpty()) {
            text = text + "\n\n（附图说明：文中【附图N】与下方多模态图片按编号一一对应。）\n";
        }
        return new Prepared(text, images);
    }

    private static String mediaTypeValue(MediaType mediaType) {
        if (mediaType == null) {
            return "image/png";
        }
        return mediaType.toString();
    }

    private record ImgRef(int index, String fileName, String alt) {}
}
