package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.pipeline.dto.PipelineInstanceVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelinePmSecondRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStartRequest;
import com.lonple.dap.server.modules.pipeline.entity.PipeDefinition;
import com.lonple.dap.server.modules.pipeline.entity.PipeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeNodeDef;
import com.lonple.dap.server.modules.pipeline.entity.PipeNodeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeRepoWorktree;
import com.lonple.dap.server.modules.pipeline.entity.PipeUnitDef;
import com.lonple.dap.server.modules.pipeline.entity.PipeUnitInstance;
import com.lonple.dap.server.modules.pipeline.mapper.PipeDefinitionMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeNodeDefMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeNodeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeRepoWorktreeMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeUnitDefMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeUnitInstanceMapper;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionCreateRequest;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionContext;
import com.lonple.dap.server.modules.chat.entity.WbSessionInput;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionContextMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionInputMapper;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.entity.SysProductCodeRepo;
import com.lonple.dap.server.modules.product.entity.SysProductVersion;
import com.lonple.dap.server.modules.issue.IssueService;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceFsService;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceFileContentVO;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNode;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.dto.WikiFileContentVO;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class PipelineService {

    private final PipeDefinitionMapper definitionMapper;
    private final PipeNodeDefMapper nodeDefMapper;
    private final PipeUnitDefMapper unitDefMapper;
    private final PipeInstanceMapper instanceMapper;
    private final PipeNodeInstanceMapper nodeInstanceMapper;
    private final PipeUnitInstanceMapper unitInstanceMapper;
    private final PipeRepoWorktreeMapper worktreeMapper;
    private final RequirementMapper requirementMapper;
    private final IssueService issueService;
    private final ProductService productService;
    private final GitWorktreeService gitWorktreeService;
    private final ObjectMapper objectMapper;
    private final PipeRuntimeService pipeRuntimeService;
    private final WikiFsService wikiFsService;
    private final WorkspaceFsService workspaceFsService;
    private final ChatSessionMapper chatSessionMapper;
    private final SessionInputMapper sessionInputMapper;
    private final SessionContextMapper sessionContextMapper;
    private final PipeUnitMaterialBinder materialBinder;
    private final PipeMergeService pipeMergeService;

    public PipelineService(
            PipeDefinitionMapper definitionMapper,
            PipeNodeDefMapper nodeDefMapper,
            PipeUnitDefMapper unitDefMapper,
            PipeInstanceMapper instanceMapper,
            PipeNodeInstanceMapper nodeInstanceMapper,
            PipeUnitInstanceMapper unitInstanceMapper,
            PipeRepoWorktreeMapper worktreeMapper,
            RequirementMapper requirementMapper,
            IssueService issueService,
            ProductService productService,
            GitWorktreeService gitWorktreeService,
            ObjectMapper objectMapper,
            @Lazy PipeRuntimeService pipeRuntimeService,
            WikiFsService wikiFsService,
            WorkspaceFsService workspaceFsService,
            ChatSessionMapper chatSessionMapper,
            SessionInputMapper sessionInputMapper,
            SessionContextMapper sessionContextMapper,
            PipeUnitMaterialBinder materialBinder,
            PipeMergeService pipeMergeService) {
        this.definitionMapper = definitionMapper;
        this.nodeDefMapper = nodeDefMapper;
        this.unitDefMapper = unitDefMapper;
        this.instanceMapper = instanceMapper;
        this.nodeInstanceMapper = nodeInstanceMapper;
        this.unitInstanceMapper = unitInstanceMapper;
        this.worktreeMapper = worktreeMapper;
        this.requirementMapper = requirementMapper;
        this.issueService = issueService;
        this.productService = productService;
        this.gitWorktreeService = gitWorktreeService;
        this.objectMapper = objectMapper;
        this.pipeRuntimeService = pipeRuntimeService;
        this.wikiFsService = wikiFsService;
        this.workspaceFsService = workspaceFsService;
        this.chatSessionMapper = chatSessionMapper;
        this.sessionInputMapper = sessionInputMapper;
        this.sessionContextMapper = sessionContextMapper;
        this.materialBinder = materialBinder;
        this.pipeMergeService = pipeMergeService;
    }

    @Transactional
    public Long start(PipelineStartRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        boolean hasReq = req.getRequirementId() != null;
        boolean hasIssue = req.getIssueId() != null;
        if (hasReq == hasIssue) {
            throw new BizException("请指定 requirementId 或 issueId（二选一）");
        }

        PipeDefinition def;
        PipeInstance inst = new PipeInstance();
        LocalDateTime now = LocalDateTime.now();
        inst.setTenantId(tenantId);
        inst.setStartedBy(TenantSupport.requireUserId());
        inst.setStatus(PipeInstanceStatus.RUNNING.name());
        inst.setOptionsJson("{\"arch\":false,\"ui\":false}");
        inst.setTotalDurationMs(0L);
        inst.setTotalTokens(0L);
        inst.setStartedAt(now);
        inst.setCreatedAt(now);
        inst.setUpdatedAt(now);

        if (hasIssue) {
            IssIssue issue = issueService.requireOwned(req.getIssueId());
            Long active = instanceMapper.selectCount(new LambdaQueryWrapper<PipeInstance>()
                    .eq(PipeInstance::getIssueId, issue.getId())
                    .in(PipeInstance::getStatus, PipeInstanceStatus.ACTIVE));
            if (active != null && active > 0) {
                throw new BizException("该问题单已有进行中的流水线，不能重复启动");
            }
            String defCode = StringUtils.hasText(req.getDefinitionCode())
                    ? req.getDefinitionCode().trim() : "ISSUE_AUTO";
            def = resolveDefinition(tenantId, defCode);
            if (!"ISSUE_AUTO".equalsIgnoreCase(def.getCode())) {
                throw new BizException("问题单仅支持 ISSUE_AUTO 流水定义");
            }
            // 修复节点需要业务仓
            if (productService.listCodeRepos(issue.getProductId()).stream()
                    .noneMatch(r -> !StringUtils.hasText(r.getRepoKind())
                            || "BIZ".equalsIgnoreCase(r.getRepoKind())
                            || "BOTH".equalsIgnoreCase(r.getRepoKind()))) {
                throw new BizException("产品未配置业务代码仓，无法启动问题流水");
            }
            inst.setIssueId(issue.getId());
            inst.setRequirementId(null);
        } else {
            ReqRequirement requirement = requireRequirement(req.getRequirementId(), tenantId);
            Long active = instanceMapper.selectCount(new LambdaQueryWrapper<PipeInstance>()
                    .eq(PipeInstance::getRequirementId, requirement.getId())
                    .in(PipeInstance::getStatus, PipeInstanceStatus.ACTIVE));
            if (active != null && active > 0) {
                throw new BizException("该需求已有进行中的流水线，不能重复启动");
            }
            def = resolveDefinition(tenantId, req.getDefinitionCode());
            inst.setRequirementId(requirement.getId());
            inst.setIssueId(null);
        }

        inst.setDefinitionId(def.getId());
        instanceMapper.insert(inst);

        boolean issueAuto = "ISSUE_AUTO".equalsIgnoreCase(def.getCode());
        List<PipeNodeDef> nodeDefs = nodeDefMapper.selectList(new LambdaQueryWrapper<PipeNodeDef>()
                .eq(PipeNodeDef::getDefinitionId, def.getId())
                .orderByAsc(PipeNodeDef::getSortOrder)
                .orderByAsc(PipeNodeDef::getId));
        List<Long> nodeDefIds = nodeDefs.stream().map(PipeNodeDef::getId).toList();
        Map<Long, List<PipeUnitDef>> unitsByNode = nodeDefIds.isEmpty()
                ? Map.of()
                : unitDefMapper.selectList(new LambdaQueryWrapper<PipeUnitDef>()
                                .in(PipeUnitDef::getNodeDefId, nodeDefIds)
                                .orderByAsc(PipeUnitDef::getSortOrder))
                        .stream()
                        .collect(Collectors.groupingBy(PipeUnitDef::getNodeDefId));

        for (PipeNodeDef nd : nodeDefs) {
            PipeNodeInstance ni = new PipeNodeInstance();
            ni.setInstanceId(inst.getId());
            ni.setNodeCode(nd.getCode());
            ni.setNodeName(nd.getName());
            ni.setKind(nd.getKind());
            ni.setOptionalFlag(Boolean.TRUE.equals(nd.getOptionalFlag()));
            ni.setEnabledFlag(true);
            ni.setSortOrder(nd.getSortOrder() == null ? 0 : nd.getSortOrder());
            if (issueAuto) {
                if ("issue_analyze".equals(nd.getCode())) {
                    ni.setStatus("WAITING_TRIGGER");
                } else if ("done".equals(nd.getCode())) {
                    ni.setStatus("PENDING");
                } else {
                    ni.setStatus("PENDING");
                }
            } else if ("pm_first".equals(nd.getCode())) {
                ni.setStatus("SUCCEEDED");
                ni.setStartedAt(now);
                ni.setFinishedAt(now);
            } else if ("ai_pre".equals(nd.getCode())) {
                ni.setStatus("WAITING_TRIGGER");
            } else {
                ni.setStatus("PENDING");
            }
            nodeInstanceMapper.insert(ni);

            List<PipeUnitDef> uds = unitsByNode.getOrDefault(nd.getId(), List.of());
            for (PipeUnitDef ud : uds) {
                PipeUnitInstance ui = new PipeUnitInstance();
                ui.setNodeInstanceId(ni.getId());
                ui.setInstanceId(inst.getId());
                ui.setUnitCode(ud.getCode());
                ui.setUnitName(ud.getName());
                ui.setPositionCode(ud.getPositionCode());
                ui.setTaskTypeCode(ud.getTaskTypeCode());
                ui.setRoundTier(ud.getRoundTier());
                ui.setMaxRound(ud.getMaxRound());
                ui.setConfirmRound(0);
                ui.setRepoKind(ud.getRepoKind() == null ? "NONE" : ud.getRepoKind());
                if (issueAuto && "issue_analyze".equals(nd.getCode())) {
                    ui.setStatus("WAITING_TRIGGER");
                } else if (!issueAuto && "ai_pre".equals(nd.getCode())) {
                    ui.setStatus("WAITING_TRIGGER");
                } else if (!issueAuto && "pm_first".equals(nd.getCode())) {
                    ui.setStatus("SUCCEEDED");
                } else {
                    ui.setStatus("PENDING");
                }
                unitInstanceMapper.insert(ui);
            }
        }

        if (hasIssue) {
            IssIssue issue = issueService.requireOwned(req.getIssueId());
            SysProduct product = productService.requireOwned(issue.getProductId());
            wikiFsService.ensureIssueTicketMarkdown(product, issue);
            wikiFsService.ensureBuiltinTemplateFile("问题原因分析模板.md");
        }

        Long instanceId = inst.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pipeRuntimeService.bootstrapAfterStart(instanceId);
                }
            });
        } else {
            pipeRuntimeService.bootstrapAfterStart(instanceId);
        }
        return instanceId;
    }

    public PipelineInstanceVO getByRequirement(Long requirementId) {
        Long tenantId = TenantSupport.requireTenantId();
        ReqRequirement requirement = requireRequirement(requirementId, tenantId);
        PipeInstance inst = instanceMapper.selectOne(new LambdaQueryWrapper<PipeInstance>()
                .eq(PipeInstance::getRequirementId, requirementId)
                .orderByDesc(PipeInstance::getId)
                .last("LIMIT 1"));
        if (inst == null) {
            return null;
        }
        return toVo(inst, requirement, null);
    }

    public PipelineInstanceVO getByIssue(Long issueId) {
        Long tenantId = TenantSupport.requireTenantId();
        IssIssue issue = issueService.requireOwned(issueId);
        PipeInstance inst = instanceMapper.selectOne(new LambdaQueryWrapper<PipeInstance>()
                .eq(PipeInstance::getIssueId, issueId)
                .orderByDesc(PipeInstance::getId)
                .last("LIMIT 1"));
        if (inst == null) {
            return null;
        }
        return toVo(inst, null, issue);
    }

    public PipelineInstanceVO get(Long instanceId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeInstance inst = requireInstance(instanceId, tenantId);
        if (inst.getIssueId() != null) {
            return toVo(inst, null, issueService.requireOwned(inst.getIssueId()));
        }
        ReqRequirement requirement = requireRequirement(inst.getRequirementId(), tenantId);
        return toVo(inst, requirement, null);
    }

    @Transactional
    public void cancel(Long instanceId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeInstance inst = requireInstance(instanceId, tenantId);
        if (PipeInstanceStatus.DONE.name().equals(inst.getStatus())
                || PipeInstanceStatus.CANCELLED.name().equals(inst.getStatus())) {
            throw new BizException("流水线已结束，无法取消");
        }
        LocalDateTime now = LocalDateTime.now();
        inst.setStatus(PipeInstanceStatus.CANCELLED.name());
        inst.setFinishedAt(now);
        inst.setUpdatedAt(now);
        inst.setWaitingReason("用户取消自动化");
        instanceMapper.updateById(inst);
    }

    /**
     * 二审：版本/时间 + 是否启用架构/UI；PASS 挂起，PASS_AND_DEV 打开 gen_prd。
     */
    @Transactional
    public void pmSecond(Long instanceId, PipelinePmSecondRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeInstance inst = requireInstance(instanceId, tenantId);
        PipeNodeInstance second = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, instanceId)
                .eq(PipeNodeInstance::getNodeCode, "pm_second"));
        if (second == null) {
            throw new BizException("缺少产品经理二审节点");
        }
        if (!"WAITING_HUMAN".equals(second.getStatus()) && !"PENDING".equals(second.getStatus())
                && !"WAITING_TRIGGER".equals(second.getStatus())) {
            // ai_pre 未完成时不允许二审
            PipeNodeInstance aiPre = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                    .eq(PipeNodeInstance::getInstanceId, instanceId)
                    .eq(PipeNodeInstance::getNodeCode, "ai_pre"));
            if (aiPre == null || !"SUCCEEDED".equals(aiPre.getStatus())) {
                throw new BizException("请先完成 AI 初审后再二审");
            }
        }

        try {
            ObjectNode opt = objectMapper.createObjectNode();
            opt.put("arch", req.isEnableArch());
            opt.put("ui", req.isEnableUi());
            inst.setOptionsJson(objectMapper.writeValueAsString(opt));
        } catch (Exception e) {
            throw new BizException("写入二审选项失败");
        }
        inst.setReleaseVersionId(req.getReleaseVersionId());
        inst.setReleaseTime(req.getReleaseTime() != null ? req.getReleaseTime() : LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        inst.setUpdatedAt(now);

        second.setStatus("SUCCEEDED");
        second.setFinishedAt(now);
        if (second.getStartedAt() == null) {
            second.setStartedAt(now);
        }
        nodeInstanceMapper.updateById(second);

        // 选定版本后：Wiki 产品下建版本目录，迁入需求文件夹，并刷新前置单元会话引用
        ReqRequirement requirement = requireRequirement(inst.getRequirementId(), tenantId);
        SysProductVersion releaseVersion = productService.requireVersionOfProduct(
                requirement.getProductId(), req.getReleaseVersionId());
        requirement.setReleaseVersion(releaseVersion.getVersion());
        requirement.setUpdatedAt(now);
        requirementMapper.updateById(requirement);
        promoteWikiToReleaseVersion(inst, requirement, releaseVersion.getVersion());

        // 可选节点启用标记
        markOptionalEnabled(instanceId, "gen_arch", req.isEnableArch());
        markOptionalEnabled(instanceId, "gen_ui", req.isEnableUi());
        markOptionalEnabled(instanceId, "arch_review", req.isEnableArch());
        markOptionalEnabled(instanceId, "ui_review", req.isEnableUi());

        String action = req.getAction().trim().toUpperCase();
        if ("PASS".equals(action)) {
            inst.setStatus(PipeInstanceStatus.SUSPENDED.name());
            inst.setWaitingReason("二审仅通过，流水线挂起");
            instanceMapper.updateById(inst);
            return;
        }
        if (!"PASS_AND_DEV".equals(action)) {
            throw new BizException("action 仅支持 PASS 或 PASS_AND_DEV");
        }

        inst.setStatus(PipeInstanceStatus.RUNNING.name());
        inst.setWaitingReason(null);
        instanceMapper.updateById(inst);

        PipeNodeInstance genPrd = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, instanceId)
                .eq(PipeNodeInstance::getNodeCode, "gen_prd"));
        if (genPrd != null) {
            genPrd.setStatus("WAITING_TRIGGER");
            genPrd.setStartedAt(now);
            nodeInstanceMapper.updateById(genPrd);
            List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                    .eq(PipeUnitInstance::getNodeInstanceId, genPrd.getId()));
            for (PipeUnitInstance u : units) {
                u.setStatus("WAITING_TRIGGER");
                unitInstanceMapper.updateById(u);
                pipeRuntimeService.enqueueRunUnit(u.getId());
            }
        }
    }

    /**
     * 仅编码/集成测试环节调用：按需求号 feat/ 或问题单号 fix/ 建分支 + worktree。
     */
    @Transactional
    public List<PipeRepoWorktree> ensureWorktrees(Long instanceId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeInstance inst = requireInstance(instanceId, tenantId);
        if (inst.getIssueId() != null) {
            return ensureWorktreesForIssue(inst, issueService.requireOwned(inst.getIssueId()));
        }
        ReqRequirement requirement = requireRequirement(inst.getRequirementId(), tenantId);
        return ensureWorktrees(inst, requirement);
    }

    public List<String> listWorktreePaths(Long instanceId, String repoKind) {
        List<PipeRepoWorktree> rows = worktreeMapper.selectList(new LambdaQueryWrapper<PipeRepoWorktree>()
                .eq(PipeRepoWorktree::getInstanceId, instanceId)
                .orderByAsc(PipeRepoWorktree::getId));
        List<String> paths = new ArrayList<>();
        for (PipeRepoWorktree w : rows) {
            if (!StringUtils.hasText(repoKind) || "NONE".equalsIgnoreCase(repoKind)
                    || repoKind.equalsIgnoreCase(w.getRepoKind())) {
                paths.add(w.getWorktreePath());
            }
        }
        // 编码默认至少要有业务仓；测试任务要有测试仓，否则回退全部
        if (paths.isEmpty()) {
            for (PipeRepoWorktree w : rows) {
                paths.add(w.getWorktreePath());
            }
        }
        return paths;
    }

    /**
     * 打开流水线相关文档：优先 Wiki；未发布时回退个人空间可见产出（hidden 文件不可开）。
     */
    public WikiFileContentVO openWikiDoc(Long instanceId, String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new BizException("文件名不能为空");
        }
        Long tenantId = TenantSupport.requireTenantId();
        PipeInstance inst = requireInstance(instanceId, tenantId);
        IssIssue issue = inst.getIssueId() == null ? null : issueService.requireOwned(inst.getIssueId());
        ReqRequirement requirement = issue != null
                ? null
                : requireRequirement(inst.getRequirementId(), tenantId);
        Long productId = issue != null ? issue.getProductId() : requirement.getProductId();
        String name = fileName.trim();
        if (!name.contains(".")) {
            name = name + ".md";
        }

        List<String> candidates = docNameCandidates(name);

        // 优先个人空间产出（含 hidden 确认档，工作台主动点开可读）
        WikiFileContentVO fromPersonal = openFromPersonalOutput(inst.getId(), name);
        if (fromPersonal != null) {
            return fromPersonal;
        }

        // 已发布 Wiki 节点直读（按文件名匹配单元正式产出）
        for (PipeUnitInstance u : unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, inst.getId()))) {
            if (u.getOutputWikiNodeId() == null) {
                continue;
            }
            try {
                PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(u.getTaskTypeCode());
                if (nameMatchesUnitDoc(candidates, spec)) {
                    return wikiFsService.readFile(u.getOutputWikiNodeId());
                }
            } catch (Exception ignored) {
                // try next
            }
        }

        SysProduct product = productService.requireOwned(productId);
        if (product.getWikiNodeId() != null && issue != null && StringUtils.hasText(issue.getIssueNo())) {
            SysWikiNode issuesRoot = wikiFsService.findChildDirectory(product.getWikiNodeId(), "issues");
            if (issuesRoot != null) {
                SysWikiNode issueDir = wikiFsService.findChildDirectory(issuesRoot.getId(), issue.getIssueNo());
                if (issueDir != null) {
                    for (String cand : candidates) {
                        SysWikiNode inIssue = wikiFsService.findChildFile(issueDir.getId(), cand);
                        if (inIssue != null) {
                            return toWikiContent(inIssue);
                        }
                    }
                }
            }
        }
        if (product.getWikiNodeId() != null && requirement != null) {
            // 只查找、不 ensure 创建，避免打开文档时误建「产品/需求编号」旧目录
            SysWikiNode rawRoot = wikiFsService.findChildDirectory(product.getWikiNodeId(), "原始需求");
            if (rawRoot != null) {
                SysWikiNode rawReq = wikiFsService.findChildDirectory(rawRoot.getId(), requirement.getReqNo());
                if (rawReq != null) {
                    for (String cand : candidates) {
                        SysWikiNode inRaw = wikiFsService.findChildFile(rawReq.getId(), cand);
                        if (inRaw != null) {
                            return toWikiContent(inRaw);
                        }
                    }
                }
            }
            String planVer = null;
            if (inst.getReleaseVersionId() != null) {
                try {
                    planVer = productService.requireVersionOfProduct(
                            product.getId(), inst.getReleaseVersionId()).getVersion();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (!StringUtils.hasText(planVer) && StringUtils.hasText(requirement.getReleaseVersion())) {
                planVer = requirement.getReleaseVersion();
            }
            if (StringUtils.hasText(planVer)) {
                SysWikiNode verRoot = wikiFsService.findChildDirectory(product.getWikiNodeId(), planVer.trim());
                if (verRoot != null) {
                    SysWikiNode verReq = wikiFsService.findChildDirectory(verRoot.getId(), requirement.getReqNo());
                    if (verReq != null) {
                        for (String cand : candidates) {
                            SysWikiNode inVer = wikiFsService.findChildFile(verReq.getId(), cand);
                            if (inVer != null) {
                                return toWikiContent(inVer);
                            }
                        }
                    }
                }
            }
            // 兼容旧布局：{产品}/{需求}（仅查找）
            SysWikiNode legacyReq = wikiFsService.findChildDirectory(
                    product.getWikiNodeId(), requirement.getReqNo());
            if (legacyReq != null) {
                for (String cand : candidates) {
                    SysWikiNode inReq = wikiFsService.findChildFile(legacyReq.getId(), cand);
                    if (inReq != null) {
                        return toWikiContent(inReq);
                    }
                }
            }
            for (String cand : candidates) {
                SysWikiNode inProduct = wikiFsService.findChildFile(product.getWikiNodeId(), cand);
                if (inProduct != null) {
                    return toWikiContent(inProduct);
                }
            }
        }
        SysWikiNode templateRoot = wikiFsService.findTopLevelDir(tenantId, "模板");
        if (templateRoot != null) {
            for (String cand : candidates) {
                SysWikiNode inTpl = wikiFsService.findChildFile(templateRoot.getId(), cand);
                if (inTpl != null) {
                    return toWikiContent(inTpl);
                }
            }
            for (SysWikiNode dir : wikiFsService.listChildDirs(templateRoot.getId())) {
                for (String cand : candidates) {
                    SysWikiNode f = wikiFsService.findChildFile(dir.getId(), cand);
                    if (f != null) {
                        return toWikiContent(f);
                    }
                }
            }
        }

        // 问题流水：点开输入/模板时按需物化，避免旧实例未写过「问题单.md」只能整段重跑
        if (issue != null) {
            if (candidates.stream().anyMatch(c -> WikiFsService.DEFAULT_ISSUE_MARKDOWN_NAME.equals(c))) {
                SysProduct p = productService.requireOwned(productId);
                wikiFsService.ensureIssueTicketMarkdown(p, issue);
                Long issueDirId = wikiFsService.ensureIssuePublishDir(p, issue.getIssueNo());
                SysWikiNode ticket = wikiFsService.findChildFile(
                        issueDirId, WikiFsService.DEFAULT_ISSUE_MARKDOWN_NAME);
                if (ticket != null) {
                    return toWikiContent(ticket);
                }
            }
            if (candidates.stream().anyMatch(c -> "问题原因分析模板.md".equals(c))) {
                Long tplId = wikiFsService.ensureBuiltinTemplateFile("问题原因分析模板.md");
                return wikiFsService.readFile(tplId);
            }
        }

        throw new BizException("未找到文档：" + name
                + "（问题修复输入「问题原因分析.md」需先完成并发布「问题定位分析」节点；"
                + "也可对该节点点「重新执行」后发布）");
    }

    /** 在流水线各单元会话输出目录中查找文件（含 hidden，供工作台点开） */
    private WikiFileContentVO openFromPersonalOutput(Long instanceId, String name) {
        List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, instanceId));
        List<String> candidates = docNameCandidates(name);
        for (PipeUnitInstance u : units) {
            PipeUnitMaterialSpec spec = null;
            try {
                spec = PipeUnitMaterialCatalog.require(u.getTaskTypeCode());
            } catch (Exception ignored) {
                // ignore
            }
            // 请求名命中本单元正式/确认产出时，优先按已记录的 nodeId 读
            if (spec != null && nameMatchesUnitDoc(candidates, spec)) {
                WikiFileContentVO byNode = readPersonalByNodeId(u);
                if (byNode != null) {
                    return byNode;
                }
            }
            if (u.getSessionId() == null) {
                continue;
            }
            WbChatSession session = chatSessionMapper.selectById(u.getSessionId());
            if (session == null || session.getOutputDirNodeId() == null) {
                continue;
            }
            for (String cand : candidates) {
                SysWorkspaceNode file = workspaceFsService.findChildFile(
                        session.getWorkspaceId(), session.getOutputDirNodeId(), cand);
                if (file == null) {
                    continue;
                }
                try {
                    WorkspaceFileContentVO content = workspaceFsService.readFileForTenant(
                            session.getWorkspaceId(), file.getId());
                    WikiFileContentVO vo = new WikiFileContentVO();
                    vo.setId(file.getId());
                    vo.setDisplayName(content.getDisplayName());
                    vo.setContent(content.getContent());
                    return vo;
                } catch (Exception ignored) {
                    // try next
                }
            }
            // 会话目录未命中文件名时，仍尝试单元正式产出节点（历史改名/别名）
            if (spec != null && nameMatchesUnitDoc(candidates, spec)) {
                WikiFileContentVO byNode = readPersonalByNodeId(u);
                if (byNode != null) {
                    return byNode;
                }
            }
        }
        return null;
    }

    private WikiFileContentVO readPersonalByNodeId(PipeUnitInstance u) {
        if (u.getOutputWorkspaceNodeId() == null || u.getSessionId() == null) {
            return null;
        }
        WbChatSession session = chatSessionMapper.selectById(u.getSessionId());
        if (session == null) {
            return null;
        }
        try {
            WorkspaceFileContentVO content = workspaceFsService.readFileForTenant(
                    session.getWorkspaceId(), u.getOutputWorkspaceNodeId());
            WikiFileContentVO vo = new WikiFileContentVO();
            vo.setId(u.getOutputWorkspaceNodeId());
            vo.setDisplayName(content.getDisplayName());
            vo.setContent(content.getContent());
            return vo;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean nameMatchesUnitDoc(List<String> candidates, PipeUnitMaterialSpec spec) {
        for (String c : candidates) {
            if (c.equalsIgnoreCase(spec.formalOutputName())
                    || c.equalsIgnoreCase(spec.confirmOutputName())) {
                return true;
            }
            // 确认轮次草稿：正式确认名-rN.md
            String base = spec.confirmOutputName() == null ? ""
                    : spec.confirmOutputName().replaceFirst("(?i)\\.md$", "");
            if (StringUtils.hasText(base) && c.matches("(?i)" + java.util.regex.Pattern.quote(base) + "-r\\d+\\.md")) {
                return true;
            }
        }
        return false;
    }

    /** 打开文档时的候选文件名（含历史短名别名、去轮次后缀） */
    private static List<String> docNameCandidates(String name) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(name);
        if (name.matches("(?i).+-r\\d+\\.md$")) {
            set.add(name.replaceFirst("(?i)-r\\d+(\\.md)$", "$1"));
        }
        // 详设/用例评审：完整名 ↔ 历史短名
        Map<String, String> aliases = Map.ofEntries(
                Map.entry("详细设计评审-架构-确认.md", "详设评审-架构-确认.md"),
                Map.entry("详细设计评审-产品-确认.md", "详设评审-产品-确认.md"),
                Map.entry("详细设计评审-测试-确认.md", "详设评审-测试-确认.md"),
                Map.entry("测试用例评审-架构-确认.md", "用例评审-架构-确认.md"),
                Map.entry("测试用例评审-产品-确认.md", "用例评审-产品-确认.md"),
                Map.entry("测试用例评审-研发-确认.md", "用例评审-研发-确认.md")
        );
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                set.add(e.getValue());
            } else if (name.equalsIgnoreCase(e.getValue())) {
                set.add(e.getKey());
            }
        }
        return new ArrayList<>(set);
    }

    private WikiFileContentVO toWikiContent(SysWikiNode node) {
        return wikiFsService.readFile(node.getId());
    }

    /**
     * 二审选版本：产品 Wiki 下创建「{计划版本}」，把「{需求编号}」文件夹迁入其下，
     * 并合并「原始需求/{需求编号}」内容；随后刷新本流水线已建会话的 displayPath 引用。
     */
    private void promoteWikiToReleaseVersion(
            PipeInstance inst, ReqRequirement requirement, String planVersion) {
        SysProduct product = productService.requireOwned(requirement.getProductId());
        if (product.getWikiNodeId() == null) {
            Long productDirId = wikiFsService.ensureProductDir(product);
            product.setWikiNodeId(productDirId);
            productService.updateWikiNodeId(product.getId(), productDirId);
        }
        Long reqDirId = wikiFsService.promoteRequirementFolderToVersion(product, requirement, planVersion);
        refreshPipeSessionWikiPaths(inst.getId(), reqDirId);
    }

    /** 刷新本实例相关会话的输入/上下文 displayPath（节点 ID 不变，路径随目录搬家更新） */
    private void refreshPipeSessionWikiPaths(Long instanceId, Long reqDirId) {
        List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, instanceId));
        List<Long> sessionIds = units.stream()
                .map(PipeUnitInstance::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return;
        }
        for (Long sessionId : sessionIds) {
            List<WbSessionInput> inputs = sessionInputMapper.selectList(new LambdaQueryWrapper<WbSessionInput>()
                    .eq(WbSessionInput::getSessionId, sessionId));
            for (WbSessionInput in : inputs) {
                if (in.getWikiNodeId() == null) {
                    continue;
                }
                try {
                    SysWikiNode node = wikiFsService.requireNode(in.getWikiNodeId());
                    String path = wikiFsService.buildDisplayPath(node);
                    if (!Objects.equals(path, in.getDisplayPath())) {
                        in.setDisplayPath(path);
                        sessionInputMapper.updateById(in);
                    }
                } catch (Exception ignored) {
                    // 节点已删则跳过
                }
            }
            List<WbSessionContext> contexts = sessionContextMapper.selectList(
                    new LambdaQueryWrapper<WbSessionContext>()
                            .eq(WbSessionContext::getSessionId, sessionId));
            for (WbSessionContext ctx : contexts) {
                if (ctx.getWikiNodeId() == null) {
                    continue;
                }
                try {
                    SysWikiNode node = wikiFsService.requireNode(ctx.getWikiNodeId());
                    String path = wikiFsService.buildDisplayPath(node);
                    if (!Objects.equals(path, ctx.getDisplayPath())) {
                        ctx.setDisplayPath(path);
                        sessionContextMapper.updateById(ctx);
                    }
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
    }

    /** 产品配置的主仓本地路径（设计/详设用，不创建 worktree）。 */
    public List<String> listProductRepoPaths(Long productId, String repoKind) {
        List<String> paths = new ArrayList<>();
        for (SysProductCodeRepo repo : productService.listCodeRepos(productId)) {
            if (!StringUtils.hasText(repo.getLocalPath())) {
                continue;
            }
            String kind = StringUtils.hasText(repo.getRepoKind()) ? repo.getRepoKind() : "BIZ";
            if (!StringUtils.hasText(repoKind) || "NONE".equalsIgnoreCase(repoKind)
                    || repoKind.equalsIgnoreCase(kind)
                    || "BOTH".equalsIgnoreCase(repoKind)) {
                paths.add(repo.getLocalPath().trim());
            }
        }
        return paths;
    }

    private List<PipeRepoWorktree> ensureWorktrees(PipeInstance inst, ReqRequirement requirement) {
        return ensureWorktreesInternal(
                inst,
                requirement.getProductId(),
                requirement.getReqNo(),
                requirement.getId(),
                null,
                GitWorktreeService.BranchPrefix.FEAT);
    }

    private List<PipeRepoWorktree> ensureWorktreesForIssue(PipeInstance inst, IssIssue issue) {
        return ensureWorktreesInternal(
                inst,
                issue.getProductId(),
                issue.getIssueNo(),
                null,
                issue.getId(),
                GitWorktreeService.BranchPrefix.FIX);
    }

    private List<PipeRepoWorktree> ensureWorktreesInternal(
            PipeInstance inst,
            Long productId,
            String refNo,
            Long requirementId,
            Long issueId,
            GitWorktreeService.BranchPrefix prefix) {
        List<SysProductCodeRepo> repos = productService.listCodeRepos(productId);
        if (repos.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<PipeRepoWorktree> result = new ArrayList<>();
        for (SysProductCodeRepo repo : repos) {
            PipeRepoWorktree existing = worktreeMapper.selectOne(new LambdaQueryWrapper<PipeRepoWorktree>()
                    .eq(PipeRepoWorktree::getInstanceId, inst.getId())
                    .eq(PipeRepoWorktree::getProductCodeRepoId, repo.getId()));
            if (existing != null) {
                result.add(existing);
                continue;
            }
            String kind = StringUtils.hasText(repo.getRepoKind()) ? repo.getRepoKind() : "BIZ";
            GitWorktreeService.WorktreeResult wt =
                    gitWorktreeService.ensureWorktree(repo.getLocalPath(), refNo, kind, prefix);
            PipeRepoWorktree row = new PipeRepoWorktree();
            row.setInstanceId(inst.getId());
            row.setRequirementId(requirementId);
            row.setIssueId(issueId);
            row.setProductCodeRepoId(repo.getId());
            row.setRepoKind(kind);
            row.setBranchName(wt.branchName());
            row.setWorktreePath(wt.worktreePath());
            row.setBaseLocalPath(repo.getLocalPath());
            row.setStatus("READY");
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            worktreeMapper.insert(row);
            result.add(row);
        }
        return result;
    }

    private void markOptionalEnabled(Long instanceId, String nodeCode, boolean enabled) {
        PipeNodeInstance n = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, instanceId)
                .eq(PipeNodeInstance::getNodeCode, nodeCode));
        if (n == null) {
            return;
        }
        n.setEnabledFlag(enabled);
        if (!enabled) {
            n.setStatus("SKIPPED");
        }
        nodeInstanceMapper.updateById(n);
    }

    private PipelineInstanceVO toVo(PipeInstance inst, ReqRequirement requirement, IssIssue issue) {
        PipeDefinition def = definitionMapper.selectById(inst.getDefinitionId());
        PipelineInstanceVO vo = new PipelineInstanceVO();
        vo.setId(inst.getId());
        if (issue != null) {
            vo.setIssueId(issue.getId());
            vo.setIssueNo(issue.getIssueNo());
            vo.setProductId(issue.getProductId());
            vo.setRequirementId(issue.getRequirementId());
            if (issue.getRequirementId() != null) {
                ReqRequirement linked = requirementMapper.selectById(issue.getRequirementId());
                if (linked != null) {
                    vo.setReqNo(linked.getReqNo());
                    vo.setRequirementName(linked.getName());
                }
            }
        } else if (requirement != null) {
            vo.setRequirementId(requirement.getId());
            vo.setProductId(requirement.getProductId());
            vo.setReqNo(requirement.getReqNo());
            vo.setRequirementName(requirement.getName());
        }
        vo.setDefinitionId(inst.getDefinitionId());
        if (def != null) {
            vo.setDefinitionCode(def.getCode());
            vo.setDefinitionName(def.getName());
        }
        vo.setStatus(inst.getStatus());
        vo.setWaitingReason(inst.getWaitingReason());
        vo.setOptionsJson(inst.getOptionsJson());
        vo.setStartedAt(inst.getStartedAt());
        vo.setFinishedAt(inst.getFinishedAt());

        List<PipeNodeInstance> nodes = nodeInstanceMapper.selectList(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, inst.getId())
                .orderByAsc(PipeNodeInstance::getSortOrder)
                .orderByAsc(PipeNodeInstance::getId));
        List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, inst.getId()));
        Map<Long, List<PipeUnitInstance>> byNode = units.stream()
                .collect(Collectors.groupingBy(PipeUnitInstance::getNodeInstanceId));

        // 批量拉取各单元会话上下文路径，避免 N+1
        List<Long> sessionIds = units.stream()
                .map(PipeUnitInstance::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, List<String>> contextPathsBySession = new java.util.HashMap<>();
        if (!sessionIds.isEmpty()) {
            List<WbSessionContext> allCtx = sessionContextMapper.selectList(
                    new LambdaQueryWrapper<WbSessionContext>()
                            .in(WbSessionContext::getSessionId, sessionIds)
                            .orderByAsc(WbSessionContext::getId));
            for (WbSessionContext c : allCtx) {
                String path = c.getDisplayPath();
                if (!StringUtils.hasText(path) && c.getWikiNodeId() != null) {
                    try {
                        SysWikiNode node = wikiFsService.requireNode(c.getWikiNodeId());
                        path = wikiFsService.buildDisplayPath(node);
                        if (StringUtils.hasText(path) && !Objects.equals(path, c.getDisplayPath())) {
                            c.setDisplayPath(path);
                            sessionContextMapper.updateById(c);
                        }
                    } catch (Exception ignored) {
                        path = null;
                    }
                }
                if (!StringUtils.hasText(path)) {
                    continue;
                }
                contextPathsBySession
                        .computeIfAbsent(c.getSessionId(), k -> new ArrayList<>())
                        .add(path);
            }
        }

        for (PipeNodeInstance n : nodes) {
            PipelineInstanceVO.NodeVO nv = new PipelineInstanceVO.NodeVO();
            nv.setId(n.getId());
            nv.setNodeCode(n.getNodeCode());
            nv.setNodeName(n.getNodeName());
            nv.setKind(n.getKind());
            nv.setStatus(n.getStatus());
            nv.setOptionalFlag(n.getOptionalFlag());
            nv.setEnabledFlag(n.getEnabledFlag());
            nv.setSortOrder(n.getSortOrder());
            List<PipeUnitInstance> ul = byNode.getOrDefault(n.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(PipeUnitInstance::getId))
                    .toList();
            for (PipeUnitInstance u : ul) {
                PipelineInstanceVO.UnitVO uv = new PipelineInstanceVO.UnitVO();
                uv.setId(u.getId());
                uv.setUnitCode(u.getUnitCode());
                uv.setUnitName(u.getUnitName());
                uv.setPositionCode(u.getPositionCode());
                uv.setTaskTypeCode(u.getTaskTypeCode());
                uv.setStatus(u.getStatus());
                uv.setRoundTier(u.getRoundTier());
                uv.setMaxRound(u.getMaxRound());
                uv.setConfirmRound(u.getConfirmRound());
                uv.setRepoKind(u.getRepoKind());
                uv.setSessionId(u.getSessionId());
                uv.setOutputWorkspaceNodeId(u.getOutputWorkspaceNodeId());
                uv.setOutputWikiNodeId(u.getOutputWikiNodeId());
                uv.setErrorMessage(u.getErrorMessage());
                if (u.getSessionId() != null) {
                    List<String> paths = contextPathsBySession.getOrDefault(u.getSessionId(), List.of());
                    if (paths.isEmpty()) {
                        paths = resolveAndBackfillContextPaths(u, inst, requirement);
                        if (!paths.isEmpty()) {
                            contextPathsBySession.put(u.getSessionId(), paths);
                        }
                    }
                    uv.setContextPaths(paths);
                }
                nv.getUnits().add(uv);
            }
            vo.getNodes().add(nv);
        }

        vo.setTrackMerges(pipeMergeService.listForInstance(inst.getId()));

        List<PipeRepoWorktree> wts = worktreeMapper.selectList(new LambdaQueryWrapper<PipeRepoWorktree>()
                .eq(PipeRepoWorktree::getInstanceId, inst.getId())
                .orderByAsc(PipeRepoWorktree::getId));
        for (PipeRepoWorktree w : wts) {
            PipelineInstanceVO.WorktreeVO wv = new PipelineInstanceVO.WorktreeVO();
            wv.setId(w.getId());
            wv.setProductCodeRepoId(w.getProductCodeRepoId());
            wv.setRepoKind(w.getRepoKind());
            wv.setBranchName(w.getBranchName());
            wv.setWorktreePath(w.getWorktreePath());
            wv.setBaseLocalPath(w.getBaseLocalPath());
            wv.setStatus(w.getStatus());
            vo.getWorktrees().add(wv);
        }
        return vo;
    }

    private PipeDefinition resolveDefinition(Long tenantId, String code) {
        String defCode = StringUtils.hasText(code) ? code.trim() : "RD_AUTO";
        // 租户覆盖优先，否则平台种子
        PipeDefinition tenantDef = definitionMapper.selectOne(new LambdaQueryWrapper<PipeDefinition>()
                .eq(PipeDefinition::getTenantId, tenantId)
                .eq(PipeDefinition::getCode, defCode)
                .eq(PipeDefinition::getEnabled, true)
                .orderByDesc(PipeDefinition::getVersion)
                .last("LIMIT 1"));
        if (tenantDef != null) {
            return tenantDef;
        }
        PipeDefinition platform = definitionMapper.selectOne(new LambdaQueryWrapper<PipeDefinition>()
                .isNull(PipeDefinition::getTenantId)
                .eq(PipeDefinition::getCode, defCode)
                .eq(PipeDefinition::getEnabled, true)
                .orderByDesc(PipeDefinition::getVersion)
                .last("LIMIT 1"));
        if (platform == null) {
            throw new BizException("未找到启用的流水线定义：" + defCode);
        }
        return platform;
    }

    private ReqRequirement requireRequirement(Long id, Long tenantId) {
        ReqRequirement r = requirementMapper.selectById(id);
        if (r == null || !Objects.equals(r.getTenantId(), tenantId)) {
            throw new BizException("需求不存在");
        }
        return r;
    }

    private PipeInstance requireInstance(Long id, Long tenantId) {
        PipeInstance inst = instanceMapper.selectById(id);
        if (inst == null || !Objects.equals(inst.getTenantId(), tenantId)) {
            throw new BizException("流水线实例不存在");
        }
        return inst;
    }

    /**
     * 会话已创建但 wb_session_context 为空/无路径时：按材料目录补绑并返回展示路径。
     * 若会话里已有行但 display_path 空，则按 wikiNodeId 重建。
     */
    private List<String> resolveAndBackfillContextPaths(
            PipeUnitInstance unit, PipeInstance inst, ReqRequirement requirement) {
        List<String> paths = new ArrayList<>();
        Long sessionId = unit.getSessionId();
        if (sessionId == null) {
            return paths;
        }
        List<WbSessionContext> existing = sessionContextMapper.selectList(
                new LambdaQueryWrapper<WbSessionContext>()
                        .eq(WbSessionContext::getSessionId, sessionId)
                        .orderByAsc(WbSessionContext::getId));
        if (!existing.isEmpty()) {
            for (WbSessionContext c : existing) {
                String path = c.getDisplayPath();
                if (!StringUtils.hasText(path) && c.getWikiNodeId() != null) {
                    try {
                        SysWikiNode node = wikiFsService.requireNode(c.getWikiNodeId());
                        path = wikiFsService.buildDisplayPath(node);
                        if (StringUtils.hasText(path)) {
                            c.setDisplayPath(path);
                            sessionContextMapper.updateById(c);
                        } else if (StringUtils.hasText(node.getDisplayName())) {
                            path = node.getDisplayName();
                        }
                    } catch (Exception ignored) {
                        path = null;
                    }
                }
                if (StringUtils.hasText(path)) {
                    paths.add(path);
                }
            }
            return paths;
        }
        // 无上下文行：按目录配置补绑
        try {
            WbChatSession session = chatSessionMapper.selectById(sessionId);
            if (session == null || session.getWorkspaceId() == null) {
                return paths;
            }
            WorkspaceSessionCreateRequest req = new WorkspaceSessionCreateRequest();
            materialBinder.bindToCreateRequest(req, session.getWorkspaceId(), inst, unit, requirement);
            if (req.getContexts() == null || req.getContexts().isEmpty()) {
                return paths;
            }
            LocalDateTime now = LocalDateTime.now();
            for (WorkspaceSessionCreateRequest.SessionContextRef ref : req.getContexts()) {
                if (ref == null || ref.getWikiNodeId() == null) {
                    continue;
                }
                SysWikiNode node = wikiFsService.requireNode(ref.getWikiNodeId());
                String path = wikiFsService.buildDisplayPath(node);
                WbSessionContext row = new WbSessionContext();
                row.setSessionId(sessionId);
                row.setWikiNodeId(node.getId());
                row.setNodeType(node.getNodeType());
                row.setDisplayPath(path);
                row.setCreatedAt(now);
                sessionContextMapper.insert(row);
                if (StringUtils.hasText(path)) {
                    paths.add(path);
                }
            }
        } catch (Exception ignored) {
            // 展示兜底失败不影响主流程
        }
        return paths;
    }
}
