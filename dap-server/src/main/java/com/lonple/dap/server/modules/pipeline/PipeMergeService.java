package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.pipeline.dto.PipelineTrackMergeVO;
import com.lonple.dap.server.modules.pipeline.entity.PipeConfirmRound;
import com.lonple.dap.server.modules.pipeline.entity.PipeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeNodeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeTrackMerge;
import com.lonple.dap.server.modules.pipeline.entity.PipeUnitInstance;
import com.lonple.dap.server.modules.pipeline.mapper.PipeConfirmRoundMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeNodeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeTrackMergeMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeUnitInstanceMapper;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 轨内评审齐后：归并各岗 findings → 等人勾选 → 写《合并评审》Wiki → 启修订。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipeMergeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PipeTrackMergeMapper trackMergeMapper;
    private final PipeNodeInstanceMapper nodeInstanceMapper;
    private final PipeUnitInstanceMapper unitInstanceMapper;
    private final PipeConfirmRoundMapper confirmRoundMapper;
    private final RequirementMapper requirementMapper;
    private final ProductService productService;
    private final WikiFsService wikiFsService;
    private final PipeUnitMaterialBinder materialBinder;
    private final ObjectMapper objectMapper;

    /**
     * Job LLM_MERGE：收集本轨评审 findings，归并相似项，进入 WAITING_HUMAN。
     */
    @Transactional
    public void runMerge(PipeInstance inst, String trackCode) {
        PipeTrackMergeCatalog.TrackSpec spec = PipeTrackMergeCatalog.require(trackCode);
        PipeNodeInstance reviewNode = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, inst.getId())
                .eq(PipeNodeInstance::getNodeCode, spec.reviewNodeCode())
                .last("LIMIT 1"));
        if (reviewNode == null || !"SUCCEEDED".equals(reviewNode.getStatus())) {
            throw new BizException("评审节点尚未成功，无法合并：" + spec.reviewNodeCode());
        }

        List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, inst.getId())
                .eq(PipeUnitInstance::getNodeInstanceId, reviewNode.getId())
                .orderByAsc(PipeUnitInstance::getId));

        ArrayNode merged = collectAndMergeFindings(units, spec);
        String llmJson;
        try {
            llmJson = objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            throw new BizException("合并结果序列化失败");
        }

        PipeTrackMerge merge = trackMergeMapper.selectOne(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, inst.getId())
                .eq(PipeTrackMerge::getTrackCode, spec.trackCode())
                .last("LIMIT 1"));
        if (merge == null) {
            merge = new PipeTrackMerge();
            merge.setInstanceId(inst.getId());
            merge.setTrackCode(spec.trackCode());
            merge.setCreatedAt(LocalDateTime.now());
        }
        merge.setStatus("WAITING_HUMAN");
        merge.setLlmMergeJson(llmJson);
        // 默认全部标有价值，等人确认时可改
        merge.setItemsJson(llmJson);
        if (merge.getId() == null) {
            trackMergeMapper.insert(merge);
        } else {
            trackMergeMapper.updateById(merge);
        }

        inst.setStatus(PipeInstanceStatus.WAITING_HUMAN.name());
        inst.setWaitingReason("轨合并确认：" + spec.label() + " → " + spec.mergeFileName());
        log.info("pipe merge ready instance={} track={} items={}", inst.getId(), spec.trackCode(), merged.size());
    }

    /**
     * 人确认有价值项 → 写 Wiki 合并评审文件 → 返回是否需要启动修订。
     *
     * @return true 需要启动修订单元；false 无有效项已跳过修订
     */
    @Transactional
    public ConfirmResult confirm(
            PipeInstance inst,
            String trackCode,
            String itemsJson,
            Long confirmedBy) {
        PipeTrackMergeCatalog.TrackSpec spec = PipeTrackMergeCatalog.require(trackCode);
        PipeTrackMerge merge = trackMergeMapper.selectOne(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, inst.getId())
                .eq(PipeTrackMerge::getTrackCode, spec.trackCode())
                .last("LIMIT 1"));
        if (merge == null) {
            throw new BizException("合并记录不存在");
        }
        if (!"WAITING_HUMAN".equals(merge.getStatus()) && !"QUEUED".equals(merge.getStatus())) {
            throw new BizException("合并不在待确认状态：" + merge.getStatus());
        }

        JsonNode items = readArray(itemsJson);
        if (items == null || !items.isArray()) {
            items = readArray(merge.getLlmMergeJson());
        }
        if (items == null || !items.isArray()) {
            throw new BizException("合并项格式无效");
        }
        for (JsonNode it : items) {
            if (it.has("valuable") && it.get("valuable").isNull()) {
                throw new BizException("请先对每条标记「有价值 / 无价值」");
            }
        }

        ArrayNode valuable = objectMapper.createArrayNode();
        ArrayNode discarded = objectMapper.createArrayNode();
        for (JsonNode it : items) {
            boolean keep = !it.has("valuable") || it.path("valuable").asBoolean(true);
            if (keep) {
                valuable.add(it);
            } else {
                discarded.add(it);
            }
        }

        String itemsOut;
        try {
            itemsOut = objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new BizException("确认项序列化失败");
        }

        Long tenantId = TenantSupport.requireTenantId();
        ReqRequirement requirement = requirementMapper.selectById(inst.getRequirementId());
        if (requirement == null || !Objects.equals(requirement.getTenantId(), tenantId)) {
            throw new BizException("需求不存在");
        }
        SysProduct product = productService.requireOwned(requirement.getProductId());
        PipeUnitMaterialSpec reviseSpec = PipeUnitMaterialCatalog.require(spec.reviseTaskType());
        String planVersion = materialBinder.resolvePlanVersion(inst, requirement, product);
        if (!StringUtils.hasText(planVersion)) {
            throw new BizException("缺少计划版本，无法发布合并评审");
        }
        Long publishDirId = materialBinder.ensurePublishDir(product, requirement, reviseSpec, planVersion);

        String md = buildMergeMarkdown(spec, valuable, discarded, confirmedBy);
        Long wikiId = wikiFsService.upsertMarkdownFile(publishDirId, spec.mergeFileName(), md);

        merge.setItemsJson(itemsOut);
        merge.setMergedWikiNodeId(wikiId);
        merge.setStatus("SUCCEEDED");
        merge.setConfirmedBy(confirmedBy);
        merge.setConfirmedAt(LocalDateTime.now());
        trackMergeMapper.updateById(merge);

        boolean needRevise = !valuable.isEmpty();
        log.info("pipe merge confirmed instance={} track={} file={} wikiId={} valuable={} needRevise={}",
                inst.getId(), spec.trackCode(), spec.mergeFileName(), wikiId, valuable.size(), needRevise);
        return new ConfirmResult(spec, needRevise, wikiId);
    }

    public record ConfirmResult(PipeTrackMergeCatalog.TrackSpec spec, boolean needRevise, Long wikiNodeId) {
    }

    public List<PipelineTrackMergeVO> listForInstance(Long instanceId) {
        List<PipeTrackMerge> rows = trackMergeMapper.selectList(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, instanceId)
                .orderByAsc(PipeTrackMerge::getId));
        List<PipelineTrackMergeVO> out = new ArrayList<>();
        for (PipeTrackMerge m : rows) {
            healEmptyWaitingMerge(m);
            out.add(toVo(m));
        }
        return out;
    }

    public PipelineTrackMergeVO getForInstance(Long instanceId, String trackCode) {
        PipeTrackMerge m = trackMergeMapper.selectOne(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, instanceId)
                .eq(PipeTrackMerge::getTrackCode, trackCode == null ? "" : trackCode.trim().toUpperCase(Locale.ROOT))
                .last("LIMIT 1"));
        if (m != null) {
            healEmptyWaitingMerge(m);
        }
        return m == null ? null : toVo(m);
    }

    /**
     * 兼容旧占位合并（llm_merge_json=[]）：待确认且为空时按确认轮次重新归并。
     */
    private void healEmptyWaitingMerge(PipeTrackMerge merge) {
        if (merge == null || !"WAITING_HUMAN".equals(merge.getStatus())) {
            return;
        }
        JsonNode existing = readArray(merge.getLlmMergeJson());
        if (existing != null && existing.isArray() && !existing.isEmpty()) {
            return;
        }
        PipeTrackMergeCatalog.TrackSpec spec = PipeTrackMergeCatalog.of(merge.getTrackCode());
        if (spec == null) {
            return;
        }
        try {
            PipeNodeInstance reviewNode = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                    .eq(PipeNodeInstance::getInstanceId, merge.getInstanceId())
                    .eq(PipeNodeInstance::getNodeCode, spec.reviewNodeCode())
                    .last("LIMIT 1"));
            if (reviewNode == null || !"SUCCEEDED".equals(reviewNode.getStatus())) {
                return;
            }
            List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                    .eq(PipeUnitInstance::getInstanceId, merge.getInstanceId())
                    .eq(PipeUnitInstance::getNodeInstanceId, reviewNode.getId())
                    .orderByAsc(PipeUnitInstance::getId));
            ArrayNode merged = collectAndMergeFindings(units, spec);
            String llmJson = objectMapper.writeValueAsString(merged);
            merge.setLlmMergeJson(llmJson);
            merge.setItemsJson(llmJson);
            trackMergeMapper.updateById(merge);
            log.info("healed empty track merge id={} track={} items={}", merge.getId(), merge.getTrackCode(), merged.size());
        } catch (Exception e) {
            log.warn("heal empty track merge failed id={}: {}", merge.getId(), e.getMessage());
        }
    }

    private PipelineTrackMergeVO toVo(PipeTrackMerge m) {
        PipelineTrackMergeVO vo = new PipelineTrackMergeVO();
        vo.setId(m.getId());
        vo.setTrackCode(m.getTrackCode());
        PipeTrackMergeCatalog.TrackSpec spec = PipeTrackMergeCatalog.of(m.getTrackCode());
        if (spec != null) {
            vo.setLabel(spec.label());
            vo.setReviewNodeCode(spec.reviewNodeCode());
            vo.setMergeFileName(spec.mergeFileName());
        }
        vo.setStatus(m.getStatus());
        vo.setLlmMergeJson(m.getLlmMergeJson());
        vo.setItemsJson(m.getItemsJson());
        vo.setMergedWikiNodeId(m.getMergedWikiNodeId());
        vo.setConfirmedAt(m.getConfirmedAt());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }

    private ArrayNode collectAndMergeFindings(List<PipeUnitInstance> units, PipeTrackMergeCatalog.TrackSpec spec) {
        List<RawFinding> raws = new ArrayList<>();
        for (PipeUnitInstance unit : units) {
            if (!"SUCCEEDED".equals(unit.getStatus())) {
                continue;
            }
            List<PipeConfirmRound> rounds = confirmRoundMapper.selectList(new LambdaQueryWrapper<PipeConfirmRound>()
                    .eq(PipeConfirmRound::getUnitInstanceId, unit.getId())
                    .orderByAsc(PipeConfirmRound::getRoundNo));
            for (PipeConfirmRound round : rounds) {
                JsonNode findings = readArray(round.getFindingsJson());
                JsonNode answers = readJson(round.getHumanAnswersJson());
                Map<String, Boolean> agreeById = parseAgreeMap(answers == null ? null : answers.path("findings"));
                if (findings == null || !findings.isArray()) {
                    continue;
                }
                for (JsonNode f : findings) {
                    String fid = text(f, "id");
                    if (!StringUtils.hasText(fid)) {
                        continue;
                    }
                    Boolean agree = agreeById.get(fid);
                    if (Boolean.FALSE.equals(agree)) {
                        continue; // 本岗已否决
                    }
                    String title = firstText(f, "title", "issue", "summary", "content");
                    if (!StringUtils.hasText(title)) {
                        continue;
                    }
                    RawFinding r = new RawFinding();
                    r.unitCode = unit.getUnitCode();
                    r.positionCode = unit.getPositionCode();
                    r.originalId = fid;
                    r.title = title.trim();
                    r.severity = text(f, "severity");
                    r.suggestion = firstText(f, "conclusion", "suggestion", "impact");
                    if (!StringUtils.hasText(r.suggestion)) {
                        r.suggestion = joinSuggestions(f.path("suggestions"));
                    }
                    r.recommend = text(f, "recommend");
                    raws.add(r);
                }
            }
        }

        // 按标题相似度归并
        List<MergedBucket> buckets = new ArrayList<>();
        for (RawFinding r : raws) {
            MergedBucket hit = null;
            for (MergedBucket b : buckets) {
                if (similarTitle(b.title, r.title)) {
                    hit = b;
                    break;
                }
            }
            if (hit == null) {
                hit = new MergedBucket();
                hit.title = r.title;
                hit.severity = r.severity;
                hit.suggestion = r.suggestion;
                hit.recommend = r.recommend;
                buckets.add(hit);
            } else {
                if (!StringUtils.hasText(hit.severity) && StringUtils.hasText(r.severity)) {
                    hit.severity = r.severity;
                }
                if ((!StringUtils.hasText(hit.suggestion) || "—".equals(hit.suggestion))
                        && StringUtils.hasText(r.suggestion)) {
                    hit.suggestion = r.suggestion;
                }
            }
            hit.sources.add(r);
        }

        ArrayNode out = objectMapper.createArrayNode();
        int i = 1;
        for (MergedBucket b : buckets) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", "M" + i++);
            item.put("title", b.title);
            item.put("severity", b.severity == null ? "" : b.severity);
            item.put("suggestion", b.suggestion == null ? "" : b.suggestion);
            item.put("recommend", b.recommend == null ? "" : b.recommend);
            item.put("target", spec.targetDocName());
            item.put("valuable", true); // 默认有价值
            ArrayNode sources = item.putArray("sources");
            ArrayNode reviewers = item.putArray("reviewers");
            Map<String, Boolean> seenPos = new LinkedHashMap<>();
            for (RawFinding s : b.sources) {
                ObjectNode src = sources.addObject();
                src.put("position", s.positionCode == null ? "" : s.positionCode);
                src.put("unitCode", s.unitCode == null ? "" : s.unitCode);
                src.put("originalId", s.originalId);
                src.put("title", s.title);
                String label = positionLabel(s.positionCode);
                if (!seenPos.containsKey(label)) {
                    seenPos.put(label, true);
                    reviewers.add(label);
                }
            }
            out.add(item);
        }
        return out;
    }

    private String buildMergeMarkdown(
            PipeTrackMergeCatalog.TrackSpec spec,
            ArrayNode valuable,
            ArrayNode discarded,
            Long confirmedBy) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(spec.mergeFileName().replace(".md", "")).append("\n\n");
        sb.append("> 轨：").append(spec.label())
                .append(" · 确认时间：").append(LocalDateTime.now().format(TS));
        if (confirmedBy != null) {
            sb.append(" · 确认人ID：").append(confirmedBy);
        }
        sb.append("\n\n");
        sb.append("本文档为修订 Agent（`").append(spec.reviseTaskType())
                .append("`）的必填输入；请仅针对「有价值问题」修订《")
                .append(spec.targetDocName()).append("》。\n\n");

        sb.append("## 有价值问题（进入修订）\n\n");
        sb.append("| 编号 | 问题 | 严重级别 | 建议 | 来源岗位 | 原编号 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        if (valuable == null || valuable.isEmpty()) {
            sb.append("| — | （无有效项，可跳过修订） | — | — | — | — |\n");
        } else {
            for (JsonNode it : valuable) {
                sb.append("| ").append(esc(it.path("id").asText("")))
                        .append(" | ").append(esc(it.path("title").asText("")))
                        .append(" | ").append(esc(it.path("severity").asText("")))
                        .append(" | ").append(esc(it.path("suggestion").asText("")))
                        .append(" | ").append(esc(reviewersOf(it)))
                        .append(" | ").append(esc(originalIdsOf(it)))
                        .append(" |\n");
            }
        }

        sb.append("\n## 未采纳\n\n");
        sb.append("| 编号 | 问题 | 来源岗位 |\n|---|---|---|\n");
        if (discarded == null || discarded.isEmpty()) {
            sb.append("| — | 无 | — |\n");
        } else {
            for (JsonNode it : discarded) {
                sb.append("| ").append(esc(it.path("id").asText("")))
                        .append(" | ").append(esc(it.path("title").asText("")))
                        .append(" | ").append(esc(reviewersOf(it)))
                        .append(" |\n");
            }
        }

        sb.append("\n## 机器可读（修订 Agent 参考）\n\n");
        sb.append("```json\n");
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("track", spec.trackCode());
        envelope.put("mergeFile", spec.mergeFileName());
        envelope.put("targetDoc", spec.targetDocName());
        envelope.set("valuableItems", valuable == null ? objectMapper.createArrayNode() : valuable);
        try {
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
        } catch (Exception e) {
            sb.append("{}");
        }
        sb.append("\n```\n");
        return sb.toString();
    }

    private static String reviewersOf(JsonNode it) {
        JsonNode reviewers = it.path("reviewers");
        if (reviewers.isArray() && !reviewers.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode r : reviewers) {
                parts.add(r.asText(""));
            }
            return String.join("、", parts);
        }
        JsonNode sources = it.path("sources");
        if (sources.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode s : sources) {
                parts.add(positionLabel(s.path("position").asText("")));
            }
            return String.join("、", parts);
        }
        return "";
    }

    private static String originalIdsOf(JsonNode it) {
        JsonNode sources = it.path("sources");
        if (!sources.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode s : sources) {
            String pos = positionLabel(s.path("position").asText(""));
            String oid = s.path("originalId").asText("");
            parts.add(pos + ":" + oid);
        }
        return String.join("；", parts);
    }

    private static String positionLabel(String code) {
        if (!StringUtils.hasText(code)) {
            return "未知";
        }
        return switch (code.trim().toUpperCase(Locale.ROOT)) {
            case "PM" -> "产品";
            case "ARCH" -> "架构";
            case "UI" -> "UI";
            case "DEV" -> "研发";
            case "QA" -> "测试";
            default -> code;
        };
    }

    private static boolean similarTitle(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        if (!StringUtils.hasText(na) || !StringUtils.hasText(nb)) {
            return false;
        }
        if (na.equals(nb)) {
            return true;
        }
        if (na.length() >= 6 && nb.length() >= 6 && (na.contains(nb) || nb.contains(na))) {
            return true;
        }
        return false;
    }

    private static String normalizeTitle(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}　、，。；：！？「」『』【】（）()\\[\\]{}<>《》\"']+", "")
                .trim();
    }

    private Map<String, Boolean> parseAgreeMap(JsonNode findingAnswers) {
        Map<String, Boolean> map = new HashMap<>();
        if (findingAnswers == null || !findingAnswers.isArray()) {
            return map;
        }
        for (JsonNode a : findingAnswers) {
            String id = text(a, "id");
            if (!StringUtils.hasText(id) || !a.has("agree") || a.get("agree").isNull()) {
                continue;
            }
            map.put(id, a.path("agree").asBoolean());
        }
        return map;
    }

    private JsonNode readArray(String raw) {
        JsonNode n = readJson(raw);
        return n != null && n.isArray() ? n : null;
    }

    private JsonNode readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null) {
            return "";
        }
        String v = n.path(field).asText("");
        return v == null ? "" : v.trim();
    }

    private static String firstText(JsonNode n, String... fields) {
        if (n == null) {
            return "";
        }
        for (String f : fields) {
            String v = n.path(f).asText("");
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return "";
    }

    private static String joinSuggestions(JsonNode suggestions) {
        if (suggestions == null || !suggestions.isArray() || suggestions.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode s : suggestions) {
            String t = s.isTextual() ? s.asText("") : firstText(s, "text", "content", "title");
            if (StringUtils.hasText(t)) {
                parts.add(t.trim());
            }
        }
        return String.join("；", parts);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    private static class RawFinding {
        String unitCode;
        String positionCode;
        String originalId;
        String title;
        String severity;
        String suggestion;
        String recommend;
    }

    private static class MergedBucket {
        String title;
        String severity;
        String suggestion;
        String recommend;
        List<RawFinding> sources = new ArrayList<>();
    }
}
