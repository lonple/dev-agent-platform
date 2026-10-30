package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.chat.SessionTaskType;
import com.lonple.dap.server.modules.chat.entity.WbChatMessage;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionCodeRepo;
import com.lonple.dap.server.modules.chat.entity.WbSessionContext;
import com.lonple.dap.server.modules.chat.entity.WbSessionInput;
import com.lonple.dap.server.modules.chat.mapper.ChatMessageMapper;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionCodeRepoMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionContextMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionInputMapper;
import com.lonple.dap.server.modules.pipeline.dto.PipelineAiPrepareVO;
import com.lonple.dap.server.modules.pipeline.entity.PipeArtifact;
import com.lonple.dap.server.modules.pipeline.entity.PipeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeReview;
import com.lonple.dap.server.modules.pipeline.entity.PipeReviewItem;
import com.lonple.dap.server.modules.pipeline.entity.PipeStepDef;
import com.lonple.dap.server.modules.pipeline.entity.PipeStepInstance;
import com.lonple.dap.server.modules.pipeline.mapper.PipeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeReviewItemMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeReviewMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeStepDefMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeStepInstanceMapper;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.entity.SysProductCodeRepo;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceSpaceType;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspace;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceMapper;
import com.lonple.dap.server.modules.wiki.PipelineWikiTemplateService;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.dto.WikiFileContentVO;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 人工触发 AI：prepare 建任务会话+kickoff；前端流式跑完后 finalize 落 Wiki/登记产物/推进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineAiRunner {

    private static final Pattern CHECKBOX = Pattern.compile("^\\s*-\\s*\\[([ xX])]\\s+(.+)$");

    private final PipeStepInstanceMapper stepInstanceMapper;
    private final PipeStepDefMapper stepDefMapper;
    private final PipeInstanceMapper instanceMapper;
    private final RequirementMapper requirementMapper;
    private final ProductService productService;
    private final WikiFsService wikiFsService;
    private final PipelineWikiTemplateService templateService;
    private final PipeArtifactService artifactService;
    private final PipeReviewMapper reviewMapper;
    private final PipeReviewItemMapper reviewItemMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final SessionInputMapper sessionInputMapper;
    private final SessionContextMapper sessionContextMapper;
    private final SessionCodeRepoMapper sessionCodeRepoMapper;
    private final SysWorkspaceMapper workspaceMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public PipelineAiPrepareVO prepare(Long stepInstanceId) {
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null) {
            throw new BizException(404, "步骤不存在");
        }
        if (!List.of("WAITING_TRIGGER", "FAILED").contains(step.getStatus())
                && !("RUNNING".equals(step.getStatus()) && step.getSessionId() == null)) {
            throw new BizException("步骤当前不可执行 AI：" + step.getStatus());
        }
        if (!List.of("AI_GENERATE", "AI_REVIEW", "AI_REVISE").contains(step.getExecutor())) {
            throw new BizException("非 AI 步骤");
        }
        PipeInstance inst = instanceMapper.selectById(step.getInstanceId());
        ReqRequirement requirement = requirementMapper.selectById(inst.getRequirementId());
        SysProduct product = productService.requireOwned(requirement.getProductId());
        productService.assertPipelineReady(product);

        PipeStepDef def = stepDefMapper.selectOne(new LambdaQueryWrapper<PipeStepDef>()
                .eq(PipeStepDef::getDefinitionId, inst.getDefinitionId())
                .eq(PipeStepDef::getCode, step.getStepCode()));
        if (def == null) {
            throw new BizException("步骤定义缺失");
        }

        ensureRawReqArtifact(product, requirement);
        List<String> inputKinds = resolveInputKinds(def);
        StringBuilder inputDocs = new StringBuilder();
        List<Long> inputNodeIds = new ArrayList<>();
        for (String kind : inputKinds) {
            PipeArtifact art = artifactService.findCurrent(requirement.getId(), kind);
            if (art == null || art.getWikiNodeId() == null) {
                if ("RAW_REQ".equals(kind)) {
                    inputDocs.append("### ").append(kind).append("\n")
                            .append(fallbackRequirementText(requirement)).append("\n\n");
                    continue;
                }
                throw new BizException("缺少上游产物：" + kind);
            }
            WikiFileContentVO file = wikiFsService.readFile(art.getWikiNodeId());
            inputNodeIds.add(art.getWikiNodeId());
            inputDocs.append("### ").append(kind)
                    .append("（").append(file.getDisplayName()).append(" v").append(art.getVersion()).append("）\n")
                    .append(file.getContent()).append("\n\n");
        }

        String outKind = StringUtils.hasText(def.getOutputArtifactKind())
                ? def.getOutputArtifactKind() : step.getStepCode();
        String prompt = buildPrompt(def, requirement, step.getExecutor(), outKind, inputDocs.toString());

        Long reqDirId = wikiFsService.ensureRequirementDir(product.getWikiNodeId(), requirement);
        Long workspaceId = requirePersonalWorkspace(requirement.getTenantId());
        Long sessionId = ensureTaskSession(requirement, step, def, workspaceId,
                product, reqDirId, inputNodeIds);

        LocalDateTime now = LocalDateTime.now();
        step.setStatus("RUNNING");
        step.setSessionId(sessionId);
        step.setErrorMessage(null);
        step.setStartedAt(now);
        step.setFinishedAt(null);
        step.setUpdatedAt(now);
        stepInstanceMapper.updateById(step);

        inst.setStatus("RUNNING");
        inst.setWaitingReason("AI_RUNNING");
        inst.setUpdatedAt(now);
        instanceMapper.updateById(inst);

        PipelineAiPrepareVO vo = new PipelineAiPrepareVO();
        vo.setStepInstanceId(step.getId());
        vo.setSessionId(sessionId);
        vo.setWorkspaceId(workspaceId);
        vo.setKickoffPrompt(prompt);
        return vo;
    }

    public record FinalizePayload(Long artifactId, Long reviewId) {
    }

    @Transactional
    public FinalizePayload finalizeFromSession(Long stepInstanceId) {
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null || !"RUNNING".equals(step.getStatus())) {
            throw new BizException("步骤不在 AI 执行中");
        }
        if (step.getSessionId() == null) {
            throw new BizException("步骤未绑定会话");
        }
        PipeInstance inst = instanceMapper.selectById(step.getInstanceId());
        ReqRequirement requirement = requirementMapper.selectById(inst.getRequirementId());
        SysProduct product = productService.requireOwned(requirement.getProductId());
        PipeStepDef def = stepDefMapper.selectOne(new LambdaQueryWrapper<PipeStepDef>()
                .eq(PipeStepDef::getDefinitionId, inst.getDefinitionId())
                .eq(PipeStepDef::getCode, step.getStepCode()));

        WbChatSession session = sessionMapper.selectById(step.getSessionId());
        if (session == null) {
            throw new BizException("会话不存在");
        }
        WbChatMessage lastAssistant = messageMapper.selectOne(new LambdaQueryWrapper<WbChatMessage>()
                .eq(WbChatMessage::getSessionId, step.getSessionId())
                .eq(WbChatMessage::getRole, "assistant")
                .orderByDesc(WbChatMessage::getId)
                .last("LIMIT 1"));
        if (lastAssistant != null && "streaming".equals(lastAssistant.getStatus())) {
            throw new BizException("会话仍在生成中，请等待完成");
        }

        String outKind = StringUtils.hasText(def.getOutputArtifactKind())
                ? def.getOutputArtifactKind() : step.getStepCode();
        Long reqDirId = session.getOutputWikiDirNodeId() != null
                ? session.getOutputWikiDirNodeId()
                : wikiFsService.ensureRequirementDir(product.getWikiNodeId(), requirement);

        // 优先用 Agent 写入 Wiki 输出目录的最新 md；否则回退助手正文落盘
        Long wikiNodeId = findLatestWikiOutputFile(reqDirId);
        String md;
        if (wikiNodeId != null) {
            md = wikiFsService.readFile(wikiNodeId).getContent();
        } else {
            if (lastAssistant == null || !StringUtils.hasText(lastAssistant.getContent())) {
                throw new BizException("会话未写入 Wiki 产出且无助手回复，请确认 AI 已写入输出目录");
            }
            md = extractMarkdown(lastAssistant.getContent());
            if (!StringUtils.hasText(md)) {
                throw new BizException("助手回复为空");
            }
            String fileName = outputFileName(outKind, def.getName());
            wikiNodeId = wikiFsService.upsertMarkdownFile(reqDirId, fileName, md);
        }
        PipeArtifact artifact = artifactService.register(
                requirement.getId(), outKind, wikiNodeId, step.getId(), def.getName());

        Long reviewId = null;
        if ("AI_REVIEW".equals(step.getExecutor())) {
            reviewId = persistReview(requirement.getId(), step.getId(), wikiNodeId, md);
        }
        return new FinalizePayload(artifact.getId(), reviewId);
    }

    private Long findLatestWikiOutputFile(Long reqDirId) {
        if (reqDirId == null) {
            return null;
        }
        wikiFsService.requireNode(reqDirId);
        for (SysWikiNode f : wikiFsService.listChildFiles(reqDirId)) {
            if (WikiFsService.DEFAULT_REQ_MARKDOWN_NAME.equals(f.getDisplayName())) {
                continue;
            }
            if (f.getDisplayName() != null && f.getDisplayName().toLowerCase().endsWith(".md")) {
                return f.getId();
            }
        }
        return null;
    }

    private Long requirePersonalWorkspace(Long tenantId) {
        Long userId = TenantSupport.requireUserId();
        SysWorkspace ws = workspaceMapper.selectOne(new LambdaQueryWrapper<SysWorkspace>()
                .eq(SysWorkspace::getTenantId, tenantId)
                .eq(SysWorkspace::getOwnerUserId, userId)
                .eq(SysWorkspace::getSpaceType, WorkspaceSpaceType.PERSONAL.name())
                .orderByAsc(SysWorkspace::getId)
                .last("LIMIT 1"));
        if (ws == null) {
            throw new BizException("请先创建个人工作空间，再执行流水线 AI");
        }
        return ws.getId();
    }

    private Long ensureTaskSession(ReqRequirement requirement, PipeStepInstance step, PipeStepDef def,
                                   Long workspaceId, SysProduct product, Long reqDirId,
                                   List<Long> inputNodeIds) {
        if (step.getSessionId() != null) {
            WbChatSession existing = sessionMapper.selectById(step.getSessionId());
            if (existing != null) {
                return existing.getId();
            }
        }
        LocalDateTime now = LocalDateTime.now();
        String title = requirement.getReqNo() + "-" + def.getName();
        if (title.length() > 128) {
            title = title.substring(0, 128);
        }
        Long templateNodeId = null;
        if (StringUtils.hasText(def.getTemplateWikiPath())) {
            templateNodeId = templateService.resolveTemplateFileId(def.getTemplateWikiPath());
        }
        SessionTaskType taskType = SessionTaskType.fromPipeStepCode(def.getCode());

        WbChatSession session = new WbChatSession();
        session.setTenantId(requirement.getTenantId());
        session.setUserId(TenantSupport.requireUserId());
        session.setWorkspaceId(workspaceId);
        session.setSessionType("TASK");
        session.setTitle(title);
        session.setRequirementId(requirement.getId());
        session.setTaskTypeCode(taskType == null ? null : taskType.name());
        session.setPipeStepInstanceId(step.getId());
        // 流水线：输出目录=产品 Wiki 下需求编码目录；不用个人空间 outputDir
        session.setOutputDirNodeId(null);
        session.setOutputWikiDirNodeId(reqDirId);
        session.setOutputTemplateWikiNodeId(templateNodeId);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        // 上下文：产品 Wiki 根
        if (product.getWikiNodeId() != null) {
            WbSessionContext ctx = new WbSessionContext();
            ctx.setSessionId(session.getId());
            ctx.setWikiNodeId(product.getWikiNodeId());
            SysWikiNode n = wikiFsService.requireNode(product.getWikiNodeId());
            ctx.setNodeType(n.getNodeType());
            ctx.setDisplayPath(wikiFsService.buildDisplayPath(n));
            ctx.setCreatedAt(now);
            sessionContextMapper.insert(ctx);
        }
        // 输入交付件
        for (Long nodeId : inputNodeIds) {
            WbSessionInput in = new WbSessionInput();
            in.setSessionId(session.getId());
            in.setWikiNodeId(nodeId);
            SysWikiNode n = wikiFsService.requireNode(nodeId);
            in.setDisplayPath(wikiFsService.buildDisplayPath(n));
            in.setCreatedAt(now);
            sessionInputMapper.insert(in);
        }
        // 仓库：产品配置
        int sort = 0;
        for (SysProductCodeRepo repo : productService.listCodeRepos(product.getId())) {
            WbSessionCodeRepo row = new WbSessionCodeRepo();
            row.setSessionId(session.getId());
            row.setLocalPath(repo.getLocalPath());
            row.setSortNo(sort++);
            row.setCreatedAt(now);
            sessionCodeRepoMapper.insert(row);
        }
        log.info("pipeline session ready sessionId={} wikiOutDir={} templateNode={} title={}",
                session.getId(), reqDirId, templateNodeId, title);
        return session.getId();
    }

    private void ensureRawReqArtifact(SysProduct product, ReqRequirement requirement) {
        if (artifactService.findCurrent(requirement.getId(), "RAW_REQ") != null) {
            return;
        }
        Long reqDirId = wikiFsService.ensureRequirementDir(product.getWikiNodeId(), requirement);
        SysWikiNode raw = wikiFsService.findChildFile(reqDirId, WikiFsService.DEFAULT_REQ_MARKDOWN_NAME);
        if (raw != null) {
            artifactService.register(requirement.getId(), "RAW_REQ", raw.getId(), null, "原始需求");
        }
    }

    public List<String> resolveInputKinds(PipeStepDef def) {
        List<String> fromDb = readJsonStringList(def.getInputArtifactKinds());
        if (!fromDb.isEmpty()) {
            return fromDb;
        }
        return switch (def.getCode()) {
            case "AI_PRE_REVIEW", "GEN_PRD_V1" -> List.of("RAW_REQ");
            case "REVIEW_PRD_ARCH", "GEN_ARCH_V1", "GEN_UI_V1" -> List.of("PRD");
            case "GEN_DETAIL_V1", "GEN_CASES_V1" -> List.of("PRD", "ARCH");
            case "DEV_CODING" -> List.of("DETAIL", "PRD", "ARCH");
            case "INT_TEST" -> List.of("DEV_REPORT", "CASE_AUTO");
            default -> List.of("RAW_REQ");
        };
    }

    private String buildPrompt(PipeStepDef def, ReqRequirement req, String executor,
                               String outKind, String inputDocs) {
        String role = StringUtils.hasText(def.getRolePackCode()) ? def.getRolePackCode() : "AI";
        StringBuilder sb = new StringBuilder();
        sb.append("你是研发自动化流水线中的「").append(role).append("」。\n");
        sb.append("当前需求：").append(req.getReqNo()).append(" / ").append(req.getName()).append("\n");
        sb.append("当前步骤：").append(def.getName()).append("（").append(def.getCode()).append("）\n\n");
        sb.append("## 输入文档\n\n").append(StringUtils.hasText(inputDocs) ? inputDocs : "（无）\n\n");
        sb.append("## 输出约定\n");
        sb.append("- 请严格参考会话绑定的「输出参考模板」结构撰写；\n");
        sb.append("- 若会话绑定了「规范」材料（file_index 中 role=standards / standardsContent），交付物内容必须遵守其约束，不得与规范冲突；\n");
        sb.append("- 使用写入工具把交付件写入**当前会话输出目录**（已指向本需求 Wiki 目录），文件名用中文业务名 + 步骤含义，扩展名 .md；\n");
        sb.append("- 不要写到个人工作空间；不要虚构已写入未落盘的路径。\n\n");
        if ("AI_REVIEW".equals(executor)) {
            sb.append("## 任务\n");
            sb.append("对输入做评审，严格按输出模板结构写评审书。\n");
            sb.append("- 文首「评审角色」必须填本路角色：").append(role).append("\n");
            sb.append("- 按模板**维度**填结论；仅当该维度有须跟进问题时，在其下「#### 有效评审项」列出 `- [ ]`/`- [x]`（勾选=有效=须整改）\n");
            sb.append("- 无问题的维度可写结论并留空有效项，或删掉该维度下占位勾选行\n");
            sb.append("- 多角色共用同一模板时，只输出本路角色侧重，不要替其他角色写结论\n");
        } else {
            sb.append("## 任务\n根据输入生成「").append(outKind).append("」交付件。\n");
            sb.append(structureHint(outKind));
        }
        return sb.toString();
    }

    private String structureHint(String kind) {
        return switch (kind) {
            case "PRD" -> "结构：背景、目标、范围、功能、非功能、验收标准。\n";
            case "ARCH" -> "结构：一句话架构、边界、逻辑视图、决策、数据集成、风险。\n";
            case "UI_SPEC" -> "结构：页面清单、交互、状态文案、约束。\n";
            case "DETAIL" -> "结构：模块、接口/表、实现步骤、风险。\n";
            case "CASE_AUTO" -> "结构：用例编号/前置/步骤/期望。\n";
            case "DEV_REPORT" -> "结构：改动摘要、文件清单、自测、遗留。\n";
            case "TEST_REPORT" -> "结构：范围、结果、失败项、结论。\n";
            default -> "标题清晰，按专业文档组织。\n";
        };
    }

    private Long persistReview(Long requirementId, Long stepInstanceId, Long wikiNodeId, String markdown) {
        LocalDateTime now = LocalDateTime.now();
        PipeReview review = new PipeReview();
        review.setStepInstanceId(stepInstanceId);
        review.setRequirementId(requirementId);
        review.setStatus("DRAFT");
        review.setSummaryWikiNodeId(wikiNodeId);
        review.setCreatedAt(now);
        reviewMapper.insert(review);
        List<ReviewLine> lines = parseCheckboxes(markdown);
        int i = 0;
        for (ReviewLine line : lines) {
            PipeReviewItem item = new PipeReviewItem();
            item.setReviewId(review.getId());
            item.setOrdinal(i++);
            item.setContent(line.text());
            item.setChecked(line.checked());
            item.setSource("AI");
            item.setCreatedAt(now);
            reviewItemMapper.insert(item);
        }
        return review.getId();
    }

    private List<ReviewLine> parseCheckboxes(String markdown) {
        List<ReviewLine> all = new ArrayList<>();
        if (!StringUtils.hasText(markdown)) {
            return all;
        }
        boolean inEffective = false;
        List<ReviewLine> effective = new ArrayList<>();
        for (String raw : markdown.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("##") && line.contains("有效评审")) {
                inEffective = true;
                continue;
            }
            if (line.startsWith("##") && inEffective) {
                inEffective = false;
            }
            Matcher m = CHECKBOX.matcher(raw);
            if (m.find()) {
                ReviewLine rl = new ReviewLine(m.group(2).trim(), "x".equalsIgnoreCase(m.group(1)));
                all.add(rl);
                if (inEffective) {
                    effective.add(rl);
                }
            }
        }
        return effective.isEmpty() ? all : effective;
    }

    private String extractMarkdown(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String t = raw.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return t.substring(firstNl + 1, lastFence).trim();
            }
        }
        return t;
    }

    private String outputFileName(String kind, String stepName) {
        return switch (kind) {
            case "PRE_REVIEW" -> "AI初审评审.md";
            case "PRD" -> "需求分析PRD.md";
            case "PRD_REVIEW_ARCH" -> "需求分析初审评审.md";
            case "ARCH" -> "架构设计.md";
            case "UI_SPEC" -> "UI设计规范.md";
            case "DETAIL" -> "详细设计.md";
            case "CASE_AUTO" -> "测试用例.md";
            case "DEV_REPORT" -> "开发交付摘要.md";
            case "TEST_REPORT" -> "集成测试执行摘要.md";
            default -> (StringUtils.hasText(stepName) ? stepName : kind) + ".md";
        };
    }

    private String fallbackRequirementText(ReqRequirement req) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(req.getName()).append("\n\n");
        sb.append("- 编号：").append(req.getReqNo()).append("\n\n");
        if (StringUtils.hasText(req.getDescription())) {
            sb.append(req.getDescription());
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> readJsonStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private record ReviewLine(String text, boolean checked) {
    }
}
