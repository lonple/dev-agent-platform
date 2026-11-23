package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.integration.agent.AgentClient;
import com.lonple.dap.server.modules.chat.WorkspaceSessionService;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionCreateRequest;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.entity.WbSessionContext;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.chat.mapper.SessionContextMapper;
import com.lonple.dap.server.modules.pipeline.dto.PipelineConfirmRequest;
import com.lonple.dap.server.modules.pipeline.entity.PipeConfirmRound;
import com.lonple.dap.server.modules.pipeline.entity.PipeRepoWorktree;
import com.lonple.dap.server.modules.pipeline.entity.PipeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeJob;
import com.lonple.dap.server.modules.pipeline.entity.PipeNodeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeTrackMerge;
import com.lonple.dap.server.modules.pipeline.entity.PipeUnitInstance;
import com.lonple.dap.server.modules.pipeline.mapper.PipeConfirmRoundMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeJobMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeNodeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeTrackMergeMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeUnitInstanceMapper;
import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.prompt.PromptService;
import com.lonple.dap.server.modules.prompt.dto.PromptVO;
import com.lonple.dap.server.modules.issue.IssueMarkdownImages;
import com.lonple.dap.server.modules.issue.IssueService;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.issue.mapper.IssueMapper;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.review.review.ReviewOrchestrator;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import com.lonple.dap.server.modules.review.review.model.ReviewResult;
import com.lonple.dap.server.modules.sandbox.SandboxPipelineSupport;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tnworkspace.TenantWorkspaceService;
import com.lonple.dap.server.modules.tnworkspace.WorkspaceFsService;
import com.lonple.dap.server.modules.tnworkspace.dto.TenantWorkspaceVO;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkbenchWorkspaceCreateRequest;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspaceNode;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.TenantSupport;
import com.lonple.dap.server.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class PipeRuntimeService {

    private static final Set<String> CODING_REPO_NODES = Set.of(
            "gen_coding", "code_review", "gen_int_test",
            "issue_fix", "issue_code_review", "issue_int_test");
    private static final Set<String> CODE_REVIEW_TASKS = Set.of("GEN_CODE_REVIEW", "GEN_ISSUE_CODE_REVIEW");

    private final PipeInstanceMapper instanceMapper;
    private final PipeNodeInstanceMapper nodeInstanceMapper;
    private final PipeUnitInstanceMapper unitInstanceMapper;
    private final PipeJobMapper jobMapper;
    private final PipeConfirmRoundMapper confirmRoundMapper;
    private final PipeTrackMergeMapper trackMergeMapper;
    private final RequirementMapper requirementMapper;
    private final IssueMapper issueMapper;
    private final IssueService issueService;
    private final IssueMarkdownImages issueMarkdownImages;
    private final ChatSessionMapper sessionMapper;
    private final SessionContextMapper sessionContextMapper;
    private final WorkspaceSessionService workspaceSessionService;
    private final TenantWorkspaceService tenantWorkspaceService;
    private final PositionService positionService;
    private final PromptService promptService;
    private final ProductService productService;
    private final WikiFsService wikiFsService;
    private final WorkspaceFsService workspaceFsService;
    private final PipelineService pipelineService;
    private final PipeAgentExecutor agentExecutor;
    private final PipeUnitMaterialBinder materialBinder;
    private final PipeUnitRunRegistry unitRunRegistry;
    private final PipeMergeService pipeMergeService;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final SysUserMapper sysUserMapper;
    private final SandboxPipelineSupport sandboxPipelineSupport;
    private final ReviewOrchestrator reviewOrchestrator;
    private final GitWorktreeService gitWorktreeService;
    private final StockIntTestRunner stockIntTestRunner;

    public PipeRuntimeService(
            PipeInstanceMapper instanceMapper,
            PipeNodeInstanceMapper nodeInstanceMapper,
            PipeUnitInstanceMapper unitInstanceMapper,
            PipeJobMapper jobMapper,
            PipeConfirmRoundMapper confirmRoundMapper,
            PipeTrackMergeMapper trackMergeMapper,
            RequirementMapper requirementMapper,
            IssueMapper issueMapper,
            IssueService issueService,
            IssueMarkdownImages issueMarkdownImages,
            ChatSessionMapper sessionMapper,
            SessionContextMapper sessionContextMapper,
            WorkspaceSessionService workspaceSessionService,
            TenantWorkspaceService tenantWorkspaceService,
            PositionService positionService,
            PromptService promptService,
            ProductService productService,
            WikiFsService wikiFsService,
            WorkspaceFsService workspaceFsService,
            @Lazy PipelineService pipelineService,
            PipeAgentExecutor agentExecutor,
            PipeUnitMaterialBinder materialBinder,
            PipeUnitRunRegistry unitRunRegistry,
            PipeMergeService pipeMergeService,
            AgentClient agentClient,
            ObjectMapper objectMapper,
            SysUserMapper sysUserMapper,
            SandboxPipelineSupport sandboxPipelineSupport,
            ReviewOrchestrator reviewOrchestrator,
            GitWorktreeService gitWorktreeService,
            StockIntTestRunner stockIntTestRunner) {
        this.instanceMapper = instanceMapper;
        this.nodeInstanceMapper = nodeInstanceMapper;
        this.unitInstanceMapper = unitInstanceMapper;
        this.jobMapper = jobMapper;
        this.confirmRoundMapper = confirmRoundMapper;
        this.trackMergeMapper = trackMergeMapper;
        this.requirementMapper = requirementMapper;
        this.issueMapper = issueMapper;
        this.issueService = issueService;
        this.issueMarkdownImages = issueMarkdownImages;
        this.sessionMapper = sessionMapper;
        this.sessionContextMapper = sessionContextMapper;
        this.workspaceSessionService = workspaceSessionService;
        this.tenantWorkspaceService = tenantWorkspaceService;
        this.positionService = positionService;
        this.promptService = promptService;
        this.productService = productService;
        this.wikiFsService = wikiFsService;
        this.workspaceFsService = workspaceFsService;
        this.pipelineService = pipelineService;
        this.agentExecutor = agentExecutor;
        this.materialBinder = materialBinder;
        this.unitRunRegistry = unitRunRegistry;
        this.pipeMergeService = pipeMergeService;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.sysUserMapper = sysUserMapper;
        this.sandboxPipelineSupport = sandboxPipelineSupport;
        this.reviewOrchestrator = reviewOrchestrator;
        this.gitWorktreeService = gitWorktreeService;
        this.stockIntTestRunner = stockIntTestRunner;
    }

    public void enqueueRunUnit(Long unitInstanceId) {
        PipeUnitInstance unit = unitInstanceMapper.selectById(unitInstanceId);
        if (unit == null) {
            throw new BizException("单元不存在");
        }
        PipeNodeInstance node = unit.getNodeInstanceId() == null
                ? null : nodeInstanceMapper.selectById(unit.getNodeInstanceId());
        // gen_coding：进入编码四阶段，首 Job 为任务拆分
        String jobType = (node != null && "gen_coding".equals(node.getNodeCode()))
                ? PipeJobTypes.CODING_PLAN
                : PipeJobTypes.RUN_UNIT;
        PipeJob job = new PipeJob();
        job.setInstanceId(unit.getInstanceId());
        job.setUnitInstanceId(unitInstanceId);
        job.setJobType(jobType);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setNextRunAt(LocalDateTime.now());
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.insert(job);
    }

    /**
     * 触发或重新执行 Agent 单元。
     * 允许：待触发 / 失败 / 已完成 / 待确认 / 待发布；执行中不可打断。
     */
    @Transactional
    public void triggerUnit(Long unitId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeUnitInstance unit = requireUnit(unitId, tenantId);
        PipeInstance inst = requireInstance(unit.getInstanceId(), tenantId);
        if (PipeInstanceStatus.CANCELLED.name().equals(inst.getStatus())
                || PipeInstanceStatus.DONE.name().equals(inst.getStatus())) {
            throw new BizException("流水线已结束，无法触发单元");
        }
        if ("RUNNING".equals(unit.getStatus())) {
            throw new BizException("单元正在执行中，请稍后再试");
        }
        if (!List.of(
                "WAITING_TRIGGER", "FAILED", "PENDING",
                "SUCCEEDED", "WAITING_CONFIRM", "WAITING_PUBLISH").contains(unit.getStatus())) {
            throw new BizException("当前单元状态不可触发：" + unit.getStatus());
        }
        PipeNodeInstance node = nodeInstanceMapper.selectById(unit.getNodeInstanceId());
        if (node != null && Boolean.FALSE.equals(node.getEnabledFlag())) {
            throw new BizException("节点已跳过，无法触发");
        }
        // 编码/集成测试才建 worktree
        if (node != null && CODING_REPO_NODES.contains(node.getNodeCode())) {
            pipelineService.ensureWorktrees(unit.getInstanceId());
        }

        // 取消该单元未跑完的排队任务，避免重复执行
        cancelPendingJobsForUnit(unitId);

        // 重新执行：清确认轮次与产出指针；清空 sessionId 以便新建会话（避免两次执行混在同一窗口）
        // 注意：MyBatis-Plus 默认 updateById 忽略 null，必须用 UpdateWrapper 才能把 session_id 写成 NULL
        boolean rerun = unit.getSessionId() != null
                || "SUCCEEDED".equals(unit.getStatus())
                || "WAITING_CONFIRM".equals(unit.getStatus())
                || "WAITING_PUBLISH".equals(unit.getStatus())
                || "FAILED".equals(unit.getStatus());
        if (rerun) {
            confirmRoundMapper.delete(new LambdaQueryWrapper<PipeConfirmRound>()
                    .eq(PipeConfirmRound::getUnitInstanceId, unitId));
            // 清掉历史「确认」档，避免重跑时被「禁止 write_output 确认档」误伤（#9150）
            if (isIssueConfirmJsonTask(unit.getTaskTypeCode())) {
                purgeIssueConfirmOutputs(unit);
            }
        }
        LambdaUpdateWrapper<PipeUnitInstance> uw = new LambdaUpdateWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getId, unitId)
                .set(PipeUnitInstance::getConfirmRound, 0)
                .set(PipeUnitInstance::getErrorMessage, null)
                .set(PipeUnitInstance::getFinishedAt, null)
                .set(PipeUnitInstance::getOutputWorkspaceNodeId, null)
                .set(PipeUnitInstance::getOutputWikiNodeId, null)
                .set(PipeUnitInstance::getStatus, "WAITING_TRIGGER");
        if (rerun) {
            uw.set(PipeUnitInstance::getSessionId, null);
        }
        unitInstanceMapper.update(null, uw);
        unit.setConfirmRound(0);
        unit.setErrorMessage(null);
        unit.setFinishedAt(null);
        unit.setOutputWorkspaceNodeId(null);
        unit.setOutputWikiNodeId(null);
        if (rerun) {
            unit.setSessionId(null);
        }
        unit.setStatus("WAITING_TRIGGER");

        if (node != null) {
            node.setStatus("RUNNING");
            node.setFinishedAt(null);
            if (node.getStartedAt() == null) {
                node.setStartedAt(LocalDateTime.now());
            }
            nodeInstanceMapper.updateById(node);
        }
        inst.setStatus(PipeInstanceStatus.RUNNING.name());
        inst.setWaitingReason(null);
        inst.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(inst);

        enqueueRunUnit(unitId);
    }

    /**
     * 停止正在执行的 Agent 单元：打断 Agent 流式生成，取消本单元任务，状态置为失败（可重新执行）。
     */
    @Transactional
    public void stopUnit(Long unitId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeUnitInstance unit = requireUnit(unitId, tenantId);
        if (!"RUNNING".equals(unit.getStatus())) {
            throw new BizException("单元不在执行中，无法停止：" + unit.getStatus());
        }
        PipeUnitRunRegistry.ActiveRun active = unitRunRegistry.getByUnit(unitId);
        unitRunRegistry.markStopped(unitId);
        if (active != null && StringUtils.hasText(active.runId())) {
            agentClient.stop(active.runId());
        }
        cancelJobsForUnit(unitId, "用户停止");

        unit.setStatus("FAILED");
        unit.setErrorMessage("用户已停止");
        unit.setFinishedAt(LocalDateTime.now());
        unitInstanceMapper.updateById(unit);

        PipeNodeInstance node = nodeInstanceMapper.selectById(unit.getNodeInstanceId());
        if (node != null) {
            // 同节点其他单元仍在跑则保持节点 RUNNING；否则回到可人工处理
            List<PipeUnitInstance> siblings = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                    .eq(PipeUnitInstance::getNodeInstanceId, node.getId()));
            boolean anyRunning = siblings.stream().anyMatch(u ->
                    "RUNNING".equals(u.getStatus()) && !Objects.equals(u.getId(), unitId));
            if (!anyRunning) {
                node.setStatus("WAITING_HUMAN");
                nodeInstanceMapper.updateById(node);
            }
        }
        PipeInstance inst = instanceMapper.selectById(unit.getInstanceId());
        if (inst != null && !PipeInstanceStatus.CANCELLED.name().equals(inst.getStatus())
                && !PipeInstanceStatus.DONE.name().equals(inst.getStatus())) {
            inst.setStatus(PipeInstanceStatus.WAITING_HUMAN.name());
            inst.setWaitingReason(unit.getUnitName() + " 已停止，可重新执行");
            inst.setUpdatedAt(LocalDateTime.now());
            instanceMapper.updateById(inst);
        }
    }

    private void cancelPendingJobsForUnit(Long unitId) {
        cancelJobsForUnit(unitId, "单元重新执行，取消旧任务");
    }

    private void cancelJobsForUnit(Long unitId, String reason) {
        List<PipeJob> pending = jobMapper.selectList(new LambdaQueryWrapper<PipeJob>()
                .eq(PipeJob::getUnitInstanceId, unitId)
                .in(PipeJob::getStatus, List.of("PENDING", "RUNNING")));
        LocalDateTime now = LocalDateTime.now();
        for (PipeJob j : pending) {
            j.setStatus("CANCELLED");
            j.setLastError(reason);
            j.setUpdatedAt(now);
            jobMapper.updateById(j);
        }
    }

    @Transactional
    public void confirmUnit(Long unitId, PipelineConfirmRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeUnitInstance unit = requireUnit(unitId, tenantId);
        if (!"WAITING_CONFIRM".equals(unit.getStatus())) {
            throw new BizException("单元不在待确认状态");
        }
        PipeConfirmRound round = confirmRoundMapper.selectOne(new LambdaQueryWrapper<PipeConfirmRound>()
                .eq(PipeConfirmRound::getUnitInstanceId, unitId)
                .eq(PipeConfirmRound::getRoundNo, unit.getConfirmRound())
                .last("LIMIT 1"));
        if (round == null) {
            throw new BizException("缺少本轮确认记录");
        }
        if (isCodeReview(unit.getTaskTypeCode())) {
            for (Map<String, Object> answer : req.getFindingAnswers()) {
                boolean fix = Boolean.TRUE.equals(answer.get("agree"));
                String comment = answer.get("comment") == null ? "" : String.valueOf(answer.get("comment")).trim();
                if (!fix && comment.isEmpty()) {
                    throw new BizException("选择不修改时必须填写原因");
                }
            }
        }
        if (isIntTestTask(unit.getTaskTypeCode())) {
            if (req.getFindingAnswers() == null || req.getFindingAnswers().isEmpty()) {
                throw new BizException("请对失败分析建议逐条选择同意或不同意");
            }
            for (Map<String, Object> answer : req.getFindingAnswers()) {
                boolean agree = Boolean.TRUE.equals(answer.get("agree"));
                String comment = answer.get("comment") == null ? "" : String.valueOf(answer.get("comment")).trim();
                if (!agree && comment.isEmpty()) {
                    throw new BizException("不同意某条建议时必须填写原因");
                }
            }
        }
        try {
            ObjectNode answers = objectMapper.createObjectNode();
            answers.set("findings", objectMapper.valueToTree(req.getFindingAnswers()));
            answers.set("questions", objectMapper.valueToTree(req.getQuestionAnswers()));
            round.setHumanAnswersJson(objectMapper.writeValueAsString(answers));
        } catch (Exception e) {
            throw new BizException("确认内容序列化失败");
        }
        round.setConfirmedAt(LocalDateTime.now());
        confirmRoundMapper.updateById(round);

        // 归档确认 md
        archiveConfirm(unit, round);

        unit.setStatus("RUNNING");
        unitInstanceMapper.updateById(unit);
        PipeInstance inst = instanceMapper.selectById(unit.getInstanceId());
        if (inst != null) {
            inst.setStatus(PipeInstanceStatus.RUNNING.name());
            inst.setWaitingReason(null);
            instanceMapper.updateById(inst);
        }
        PipeNodeInstance node = nodeInstanceMapper.selectById(unit.getNodeInstanceId());
        if (node != null) {
            node.setStatus("RUNNING");
            nodeInstanceMapper.updateById(node);
        }

        PipeJob job = new PipeJob();
        job.setInstanceId(unit.getInstanceId());
        job.setUnitInstanceId(unitId);
        job.setJobType(PipeJobTypes.CONTINUE_UNIT);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setNextRunAt(LocalDateTime.now());
        job.setPayloadJson(round.getHumanAnswersJson());
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.insert(job);
    }

    /** 启动后自动拉起 AI 初审单元 */
    public void bootstrapAfterStart(Long instanceId) {
        List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, instanceId)
                .eq(PipeUnitInstance::getStatus, "WAITING_TRIGGER"));
        PipeNodeInstance aiPre = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, instanceId)
                .eq(PipeNodeInstance::getNodeCode, "ai_pre"));
        PipeNodeInstance issueAnalyze = nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, instanceId)
                .eq(PipeNodeInstance::getNodeCode, "issue_analyze"));
        PipeNodeInstance bootstrapNode = aiPre != null ? aiPre : issueAnalyze;
        if (bootstrapNode != null) {
            bootstrapNode.setStatus("RUNNING");
            bootstrapNode.setStartedAt(LocalDateTime.now());
            nodeInstanceMapper.updateById(bootstrapNode);
        }
        for (PipeUnitInstance u : units) {
            if (bootstrapNode != null && Objects.equals(u.getNodeInstanceId(), bootstrapNode.getId())) {
                enqueueRunUnit(u.getId());
            }
        }
    }

    public void executeJob(PipeJob job) {
        PipeInstance inst = instanceMapper.selectById(job.getInstanceId());
        if (inst == null) {
            markJob(job, "FAILED", "实例不存在");
            return;
        }
        if (PipeInstanceStatus.CANCELLED.name().equals(inst.getStatus())) {
            markJob(job, "CANCELLED", "实例已取消");
            return;
        }
        Long actorId;
        try {
            actorId = resolveActorUserId(inst);
        } catch (BizException e) {
            markJob(job, "FAILED", e.getMessage());
            return;
        }
        SysUser actor = sysUserMapper.selectById(actorId);
        LoginUser ctx = new LoginUser();
        ctx.setUserId(actorId);
        ctx.setUsername(actor != null ? actor.getUsername() : null);
        ctx.setCurrentTenantId(inst.getTenantId());
        ctx.setPermissions(Set.of("work:access", "pipeline:operate", "pipeline:view"));
        UserContext.set(ctx);
        try {
            job.setStatus("RUNNING");
            job.setAttempts(job.getAttempts() == null ? 1 : job.getAttempts() + 1);
            job.setUpdatedAt(LocalDateTime.now());
            jobMapper.updateById(job);

            if (PipeJobTypes.CODING_ANALYZE.equals(job.getJobType())) {
                runCodingAnalyzeJob(job, inst);
            } else if (PipeJobTypes.RUN_UNIT.equals(job.getJobType())
                    || PipeJobTypes.CONTINUE_UNIT.equals(job.getJobType())
                    || PipeJobTypes.INT_TEST_RUN.equals(job.getJobType())
                    || PipeJobTypes.INT_TEST_FIX.equals(job.getJobType())
                    || PipeJobTypes.CODE_REVIEW_FIX.equals(job.getJobType())
                    || PipeJobTypes.isCodingStage(job.getJobType())) {
                runUnitJob(job, inst);
            } else if (PipeJobTypes.ADVANCE.equals(job.getJobType())) {
                advanceInstance(inst.getId());
            } else if (PipeJobTypes.LLM_MERGE.equals(job.getJobType())) {
                runMergeJob(job, inst);
            } else {
                markJob(job, "FAILED", "未知 jobType");
                return;
            }
            markJob(job, "SUCCEEDED", null);
        } catch (PipeUnitStoppedException e) {
            markJob(job, "CANCELLED", "用户已停止");
            // 单元状态已由 stopUnit 写入；若仅流侧检测到取消则兜底
            if (job.getUnitInstanceId() != null) {
                PipeUnitInstance u = unitInstanceMapper.selectById(job.getUnitInstanceId());
                if (u != null && "RUNNING".equals(u.getStatus())) {
                    u.setStatus("FAILED");
                    u.setErrorMessage("用户已停止");
                    u.setFinishedAt(LocalDateTime.now());
                    unitInstanceMapper.updateById(u);
                }
            }
        } catch (Exception e) {
            log.error("pipe job failed id={}", job.getId(), e);
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 已被用户停止：不要重试
            if (job.getUnitInstanceId() != null && unitRunRegistry.isStopped(job.getUnitInstanceId())) {
                markJob(job, "CANCELLED", "用户已停止");
                return;
            }
            PipeUnitInstance cur = job.getUnitInstanceId() == null
                    ? null : unitInstanceMapper.selectById(job.getUnitInstanceId());
            if (cur != null && "FAILED".equals(cur.getStatus())
                    && cur.getErrorMessage() != null && cur.getErrorMessage().contains("停止")) {
                markJob(job, "CANCELLED", "用户已停止");
                return;
            }
            if (job.getAttempts() != null && job.getMaxAttempts() != null
                    && job.getAttempts() >= job.getMaxAttempts()) {
                markJob(job, "FAILED", err);
                if (cur != null && "RUNNING".equals(cur.getStatus())) {
                    cur.setStatus("FAILED");
                    cur.setErrorMessage(truncate(err, 1900));
                    unitInstanceMapper.updateById(cur);
                }
            } else {
                job.setStatus("PENDING");
                job.setLastError(truncate(err, 1900));
                job.setNextRunAt(LocalDateTime.now().plusSeconds(15L * (job.getAttempts() == null ? 1 : job.getAttempts())));
                job.setUpdatedAt(LocalDateTime.now());
                jobMapper.updateById(job);
            }
        } finally {
            UserContext.clear();
        }
    }

    private void runUnitJob(PipeJob job, PipeInstance inst) throws Exception {
        PipeUnitInstance unit = unitInstanceMapper.selectById(job.getUnitInstanceId());
        if (unit == null) {
            throw new BizException("单元不存在");
        }
        PipeNodeInstance node = nodeInstanceMapper.selectById(unit.getNodeInstanceId());
        PipeSubject subject = resolveSubject(inst);
        ReqRequirement requirement = subject.requirement();

        List<com.lonple.dap.server.modules.pipeline.entity.PipeRepoWorktree> worktrees = List.of();
        if (node != null && CODING_REPO_NODES.contains(node.getNodeCode())) {
            worktrees = pipelineService.ensureWorktrees(inst.getId());
        }

        // 编码阶段 Job 重试：新开会话，避免空转历史把短预算再次撑满
        if (PipeJobTypes.isCodingStage(job.getJobType())
                && job.getAttempts() != null
                && job.getAttempts() > 1) {
            clearUnitSessionForNextCodingStage(unit);
        }
        // 存量集成测试修复轮：必须新会话，避免分析轮探读历史把修复预算吃光（#9156）
        if (isIssueStockIntTest(unit.getTaskTypeCode())
                && (PipeJobTypes.CONTINUE_UNIT.equals(job.getJobType())
                || PipeJobTypes.INT_TEST_FIX.equals(job.getJobType()))) {
            clearUnitSessionForNextCodingStage(unit);
        }
        // 编码四阶段：每阶段/任务卡独立会话（窄上下文）；unit.sessionId 指向当前焦点
        WbChatSession session = ensureSession(inst, unit, subject, node, job);
        unit.setSessionId(session.getId());
        unit.setStatus("RUNNING");
        unit.setStartedAt(unit.getStartedAt() == null ? LocalDateTime.now() : unit.getStartedAt());
        unitInstanceMapper.updateById(unit);

        boolean codeReviewRead = isCodeReview(unit.getTaskTypeCode())
                && PipeJobTypes.RUN_UNIT.equals(job.getJobType());
        if (node != null && CODING_REPO_NODES.contains(node.getNodeCode()) && !codeReviewRead) {
            String purpose = ("gen_int_test".equals(node.getNodeCode()) || "issue_int_test".equals(node.getNodeCode()))
                    ? "PIPE_IT" : "PIPE_CODE";
            try {
                sandboxPipelineSupport.acquireForCoding(
                        subject.productId(),
                        subject.refNo(),
                        session.getId(),
                        job.getId(),
                        purpose,
                        null,
                        null,
                        worktrees);
            } catch (Exception e) {
                log.warn("sandbox auto-acquire failed unit={}: {}", unit.getId(), e.getMessage());
                throw e;
            }
        }

        try {
            if (isCodeReview(unit.getTaskTypeCode()) && PipeJobTypes.RUN_UNIT.equals(job.getJobType())) {
                runCodeReviewRound(inst, unit, node, job);
                return;
            }
            if (isCodeReview(unit.getTaskTypeCode()) && PipeJobTypes.CONTINUE_UNIT.equals(job.getJobType())) {
                continueCodeReview(inst, unit, node, job);
                return;
            }
            if (isCodeReview(unit.getTaskTypeCode()) && PipeJobTypes.CODE_REVIEW_FIX.equals(job.getJobType())) {
                JsonNode fixPayload = readCodeReviewFixPayload(job.getPayloadJson());
                String fixText = fixPayload.path("fixText").asText(
                        job.getPayloadJson() == null ? "" : job.getPayloadJson());
                boolean reReview = fixPayload.path("reReview").asBoolean(true);
                String preFixHead = fixPayload.path("preFixHead").asText("");
                String fixKickoff = "只修改下列代码评审项，不要改其它代码。改完用中文简要说明修改点。"
                        + "不要输出确认 JSON，也不要写评审报告。\n\n"
                        + fixText;
                agentExecutor.run(session, fixKickoff, unit.getId(), List.of(), null);
                if (!reReview) {
                    // 仅修 LOW：不再复评，避免 nit 循环
                    persistNamedOutput(unit, "代码评审报告.md",
                            "# 代码评审报告\n\n轮次：" + (unit.getConfirmRound() == null ? 1 : unit.getConfirmRound())
                                    + "\n\n> 本轮仅修复 LOW，已跳过复评并放行。\n",
                            false);
                    markUnitSucceeded(unit, node);
                    if (inst != null) {
                        inst.setStatus(PipeInstanceStatus.RUNNING.name());
                        inst.setWaitingReason(null);
                        instanceMapper.updateById(inst);
                        enqueueAdvance(inst.getId());
                    }
                    return;
                }
                PipeJob again = newJob(inst.getId(), unit.getId(), PipeJobTypes.RUN_UNIT);
                if (StringUtils.hasText(preFixHead)) {
                    ObjectNode againPayload = objectMapper.createObjectNode();
                    againPayload.put("preFixHead", preFixHead);
                    againPayload.put("mode", "VERIFY");
                    again.setPayloadJson(objectMapper.writeValueAsString(againPayload));
                }
                jobMapper.insert(again);
                return;
            }
            // 存量集成测试：平台先跑测；通过即结束；失败等人裁定后再让 LLM 定点修，再由平台复测
            if (isIssueStockIntTest(unit.getTaskTypeCode())
                    && (PipeJobTypes.RUN_UNIT.equals(job.getJobType())
                    || PipeJobTypes.INT_TEST_RUN.equals(job.getJobType()))) {
                runIssueStockIntTestPlatformFirst(inst, unit, node, requirement, session, worktrees, job);
                return;
            }
            if (isIssueStockIntTest(unit.getTaskTypeCode())
                    && PipeJobTypes.CONTINUE_UNIT.equals(job.getJobType())) {
                runIssueStockIntTestFixThenRetest(inst, unit, node, requirement, session, worktrees, job, subject);
                return;
            }
            if (isIssueStockIntTest(unit.getTaskTypeCode())
                    && PipeJobTypes.INT_TEST_FIX.equals(job.getJobType())) {
                runIssueStockIntTestFixThenRetest(inst, unit, node, requirement, session, worktrees, job, subject);
                return;
            }
            String kickoff = buildKickoffMessage(unit, node, subject, job);
            List<Map<String, String>> images = List.of();
            if (subject.isIssue()) {
                IssIssue issue = subject.issue();
                IssueMarkdownImages.Prepared prepared = issueMarkdownImages.prepareForLlm(
                        issue.getDescription(),
                        issue.getSteps(),
                        issue.getExpectedResult(),
                        issue.getSolution());
                images = prepared.images();
                if (!images.isEmpty()) {
                    kickoff = kickoff
                            + "\n\n【问题单正文·附图已按【附图N】编号，与本消息多模态图片同号对应】\n"
                            + prepared.rewrittenText();
                }
            }
            String reply = agentExecutor.run(session, kickoff, unit.getId(), images, codingMetaForJob(job));
            handleAgentReply(inst, unit, node, requirement, reply, job);
        } finally {
            if (node != null && CODING_REPO_NODES.contains(node.getNodeCode())) {
                if ("FAILED".equals(unit.getStatus())) {
                    // 失败立刻关沙箱；worktree 保留
                    sandboxPipelineSupport.releaseForCoding(session.getId(), job.getId());
                } else if ("WAITING_CONFIRM".equals(unit.getStatus())) {
                    // 等人确认：先保留沙箱，空闲 10 分钟无人再触发则回收；worktree 始终保留
                    sandboxPipelineSupport.armConfirmIdleRelease(session.getId());
                }
                // 成功路径在 markUnitSucceeded 立刻释放
            }
        }
    }

    private void handleAgentReply(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeNodeInstance node,
            ReqRequirement requirement,
            String reply,
            PipeJob job) throws Exception {
        String jobType = job == null ? null : job.getJobType();
        if (PipeJobTypes.INT_TEST_FIX.equals(jobType)) {
            // 修复轮结束 → 清会话后自动复测（测→修→测 闭环）
            releaseCodingSessionQuietly(unit, job);
            clearUnitSessionForNextCodingStage(unit);
            unit.setStatus("RUNNING");
            unit.setFinishedAt(null);
            unit.setErrorMessage("代码已修复，自动复测中");
            unitInstanceMapper.updateById(unit);
            PipeJob retest = newJob(inst.getId(), unit.getId(), PipeJobTypes.INT_TEST_RUN);
            ObjectNode retestPayload = objectMapper.createObjectNode();
            retestPayload.put("afterFix", true);
            retestPayload.put("cycle", unit.getConfirmRound() == null ? 0 : unit.getConfirmRound());
            retest.setPayloadJson(retestPayload.toString());
            jobMapper.insert(retest);
            log.info("INT_TEST_FIX done unit={} → enqueue INT_TEST_RUN cycle={}",
                    unit.getId(), unit.getConfirmRound());
            return;
        }
        JsonNode confirm = agentExecutor.tryParseConfirmJson(reply);
        if (confirm != null && "need_human_confirm".equals(confirm.path("phase").asText())) {
            int nextRound = (unit.getConfirmRound() == null ? 0 : unit.getConfirmRound()) + 1;
            if (unit.getMaxRound() != null && nextRound > unit.getMaxRound()) {
                unit.setStatus("FAILED");
                unit.setErrorMessage("确认轮次超过上限 " + unit.getMaxRound());
                unitInstanceMapper.updateById(unit);
                failNode(node, unit.getErrorMessage());
                return;
            }
            unit.setConfirmRound(nextRound);
            unit.setStatus("WAITING_CONFIRM");
            unitInstanceMapper.updateById(unit);

            // 归一化：Agent 常吐 string[]，前端/归档需要 {id,title}/{id,question}
            String findingsJson = objectMapper.writeValueAsString(
                    normalizeConfirmFindings(confirm.path("findings")));
            String questionsJson = objectMapper.writeValueAsString(
                    normalizeConfirmQuestions(confirm.path("openQuestions")));
            PipeConfirmRound round = new PipeConfirmRound();
            round.setUnitInstanceId(unit.getId());
            round.setRoundNo(nextRound);
            round.setPhase("need_human_confirm");
            round.setFindingsJson(findingsJson);
            round.setOpenQuestionsJson(questionsJson);
            round.setCreatedAt(LocalDateTime.now());
            // 重跑/任务重试时可能已有同轮次行，改为覆盖，避免 uk 冲突导致整轮 LLM 被反复重放
            PipeConfirmRound existing = confirmRoundMapper.selectOne(new LambdaQueryWrapper<PipeConfirmRound>()
                    .eq(PipeConfirmRound::getUnitInstanceId, unit.getId())
                    .eq(PipeConfirmRound::getRoundNo, nextRound)
                    .last("LIMIT 1"));
            if (existing != null) {
                round.setId(existing.getId());
                confirmRoundMapper.updateById(round);
            } else {
                confirmRoundMapper.insert(round);
            }

            // 归档用归一化后的节点，避免 Markdown 表格空标题
            ObjectNode confirmNorm = objectMapper.createObjectNode();
            confirmNorm.put("phase", confirm.path("phase").asText("need_human_confirm"));
            confirmNorm.set("findings", objectMapper.readTree(findingsJson));
            confirmNorm.set("openQuestions", objectMapper.readTree(questionsJson));
            archiveConfirmDraft(unit, node, requirement, round, confirmNorm);

            // 同节点其他单元可并行继续跑；仅当无人在跑时节点/实例才进入等待
            refreshNodeHumanWait(node, unit.getId(),
                    unit.getUnitName() + " 等待人工确认 r" + nextRound);
            return;
        }

        // 编码四阶段中间态：链式推进，不要求「编码完成报告.md」
        if (PipeJobTypes.isCodingIntermediate(jobType)) {
            handleCodingStageProgress(inst, unit, node, reply, job);
            return;
        }

        // 产出先落个人空间（完成输出）；Wiki 需人工「发布文档」
        persistOutputIfPossible(unit, node, requirement, reply);

        // 编码汇总：报告是验收项之一，但必须全部 task_result 齐套才算结束
        if (PipeJobTypes.CODING_INTEGRATE.equals(jobType)
                || (isGenCoding(unit.getTaskTypeCode()) && !PipeJobTypes.isCodingIntermediate(jobType))) {
            List<String> missing = missingCodingTaskResults(unit);
            if (!missing.isEmpty()) {
                String firstMissing = missing.get(0);
                log.warn("coding integrate blocked unit={} missing={} → requeue {}",
                        unit.getId(), missing, firstMissing);
                clearUnitSessionForNextCodingStage(unit);
                PipeJob resume = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODING_TASK);
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("taskId", firstMissing);
                payload.put("resumeFromIntegrate", true);
                resume.setPayloadJson(payload.toString());
                jobMapper.insert(resume);
                unit.setStatus("RUNNING");
                unit.setErrorMessage("编码未完成：缺少 task_result（"
                        + String.join(", ", missing.subList(0, Math.min(8, missing.size())))
                        + (missing.size() > 8 ? "…" : "")
                        + "）。已回队补做 " + firstMissing + "；全部齐套后再交《编码完成报告.md》。");
                unit.setFinishedAt(null);
                unitInstanceMapper.updateById(unit);
                return;
            }
        }

        // 集成测试：未通过（或未交报告）→ 等人裁定「改代码兼容」还是「改测试用例」，禁止盲修
        if (isIntTestNode(node) && !PipeJobTypes.INT_TEST_FIX.equals(jobType)) {
            PipeUnitMaterialSpec itSpec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
            WbChatSession itSession = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
            String reportBody = readSessionOutputContent(itSession, itSpec.formalOutputName());
            String verdictText = (reply == null ? "" : reply) + "\n" + reportBody;
            IntTestVerdict verdict = IntTestVerdictResolver.resolve(verdictText);
            boolean needHumanStrategy = verdict == IntTestVerdict.FAILED
                    || (verdict == IntTestVerdict.UNKNOWN && !StringUtils.hasText(reportBody));
            if (needHumanStrategy) {
                String archive = StringUtils.hasText(reportBody) ? reportBody
                        : (StringUtils.hasText(reply) ? reply
                        : "（Agent 未写出集成测试报告.md；平台按未通过进入人工策略确认）");
                enterIntTestFailureHumanGate(inst, unit, node, requirement, archive, verdict);
                return;
            }
            if (verdict == IntTestVerdict.UNKNOWN && StringUtils.hasText(reportBody)) {
                unit.setStatus("FAILED");
                unit.setErrorMessage("集成测试报告缺少单独一行「结论：通过/不通过」，无法判定；请重跑并写结论行");
                unit.setFinishedAt(LocalDateTime.now());
                unitInstanceMapper.updateById(unit);
                failNode(node, unit.getErrorMessage());
                return;
            }
            // PASSED：落到下方正式产出校验与成功推进
        }

        // 无标准名正式产出时不要进入「待发布」；直接失败停下
        PipeUnitMaterialSpec outSpec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        WbChatSession outSession = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
        // 底线：本会话新写出的「确认」档（非历史残留）且未走确认 JSON → 失败
        // Agent 侧已硬拦 write_output 确认档；此处防漏网。历史文件用 updatedAt 区分（#9150 误伤）
        if (isIssueConfirmJsonTask(unit.getTaskTypeCode())
                && (unit.getConfirmRound() == null || unit.getConfirmRound() == 0)
                && outSession != null
                && outSession.getOutputDirNodeId() != null
                && outSpec.confirmOutputName() != null) {
            SysWorkspaceNode rogueConfirm = workspaceFsService.findChildFile(
                    outSession.getWorkspaceId(), outSession.getOutputDirNodeId(), outSpec.confirmOutputName());
            if (rogueConfirm != null && touchedInSession(rogueConfirm, outSession)) {
                unit.setStatus("FAILED");
                unit.setErrorMessage("禁止 write_output「" + outSpec.confirmOutputName()
                        + "」。有不确定时请在对话输出 need_human_confirm JSON，由平台进入待确认；请重新执行。");
                unit.setFinishedAt(LocalDateTime.now());
                unitInstanceMapper.updateById(unit);
                failNode(node, unit.getErrorMessage());
                log.warn("unit wrote confirm file without JSON unit={} file={}",
                        unit.getId(), outSpec.confirmOutputName());
                return;
            }
        }
        SysWorkspaceNode formal = (outSession == null || outSession.getOutputDirNodeId() == null)
                ? null
                : workspaceFsService.findChildFile(
                        outSession.getWorkspaceId(), outSession.getOutputDirNodeId(), outSpec.formalOutputName());
        // 问题修复/分析：目录里的历史正式稿不算本轮交付，必须本会话 write_output 过
        if (formal != null && isIssueConfirmJsonTask(unit.getTaskTypeCode())
                && outSession != null && !touchedInSession(formal, outSession)) {
            log.info("ignore stale formal output unit={} file={} session={}",
                    unit.getId(), outSpec.formalOutputName(), outSession.getId());
            formal = null;
        }
        if (formal == null) {
            unit.setStatus("FAILED");
            if (reply != null && reply.contains("TOOL_ROUNDS_EXCEEDED")) {
                unit.setErrorMessage("工具轮次已用尽，未完成交付（不自动汇总）。正式产出缺失："
                        + outSpec.formalOutputName());
            } else {
                unit.setErrorMessage("未写出正式产出：" + outSpec.formalOutputName()
                        + "，请重新执行并确保 write_output 使用该标准名");
            }
            unit.setFinishedAt(LocalDateTime.now());
            unitInstanceMapper.updateById(unit);
            log.warn("unit missing formal output after agent run unit={} file={}",
                    unit.getId(), outSpec.formalOutputName());
            return;
        }
        if (!Objects.equals(unit.getOutputWorkspaceNodeId(), formal.getId())) {
            unit.setOutputWorkspaceNodeId(formal.getId());
            unitInstanceMapper.updateById(unit);
        }

        // 问题修复：有报告不等于修完；未完成/自述仍 404 则回队续修（#9149）
        if ("GEN_ISSUE_FIX".equalsIgnoreCase(
                unit.getTaskTypeCode() == null ? "" : unit.getTaskTypeCode().trim())) {
            String reportBody = "";
            try {
                var vo = workspaceFsService.readFile(outSession.getWorkspaceId(), formal.getId());
                reportBody = vo == null || vo.getContent() == null ? "" : vo.getContent();
            } catch (Exception e) {
                log.warn("read issue fix report failed unit={}: {}", unit.getId(), e.getMessage());
            }
            String verdictText = (reply == null ? "" : reply) + "\n" + reportBody;
            IssueFixVerdict fixVerdict = IssueFixVerdictResolver.resolve(verdictText);
            if (fixVerdict != IssueFixVerdict.FIXED) {
                int cycle = (unit.getConfirmRound() == null ? 0 : unit.getConfirmRound()) + 1;
                if (unit.getMaxRound() != null && cycle >= unit.getMaxRound()) {
                    unit.setStatus("FAILED");
                    unit.setErrorMessage("问题修复未完成且轮次达到上限（报告须含「结论：已修复」）");
                    unit.setFinishedAt(LocalDateTime.now());
                    unitInstanceMapper.updateById(unit);
                    failNode(node, unit.getErrorMessage());
                    return;
                }
                persistNamedOutput(unit, "问题修复报告-未完成-r" + cycle + ".md",
                        StringUtils.hasText(reportBody) ? reportBody : (reply == null ? "" : reply), false);
                unit.setConfirmRound(cycle);
                unit.setStatus("RUNNING");
                unit.setErrorMessage("问题修复未完成（结论不是「已修复」或正文仍有遗留/404）。已回队续修，补齐后再交报告并写 **结论：已修复**。");
                unit.setFinishedAt(null);
                unitInstanceMapper.updateById(unit);
                PipeJob again = newJob(inst.getId(), unit.getId(), PipeJobTypes.RUN_UNIT);
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("resumeIncompleteFix", true);
                payload.put("cycle", cycle);
                payload.put("hint", "上一轮报告显示修复未完成。请继续 write_file 补齐（如 Controller/完整业务），"
                        + "确认复现路径不再 404 后，再 write_output「问题修复报告.md」，文末必须单独一行 **结论：已修复**。"
                        + "若仍做不完，写 **结论：未完成**（平台会再续修，不会当成功结束）。");
                again.setPayloadJson(payload.toString());
                jobMapper.insert(again);
                log.warn("issue fix incomplete unit={} verdict={} → requeue RUN_UNIT cycle={}",
                        unit.getId(), fixVerdict, cycle);
                return;
            }
        }

        markUnitOutputReady(unit, node, inst);
    }

    /**
     * 手动将个人空间产出（正式文档 + 确认归档）发布到 Wiki，单元成功后推进流水线。
     */
    @Transactional
    public void publishUnitOutput(Long unitId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeUnitInstance unit = requireUnit(unitId, tenantId);
        if (!"WAITING_PUBLISH".equals(unit.getStatus())) {
            throw new BizException("单元不在待发布状态：" + unit.getStatus());
        }
        PipeInstance inst = instanceMapper.selectById(unit.getInstanceId());
        if (inst == null) {
            throw new BizException("流水线实例不存在");
        }
        PipeSubject subject = resolveSubject(inst);
        PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        WbChatSession session = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
        if (session == null || session.getOutputDirNodeId() == null) {
            throw new BizException("缺少会话输出目录，无法发布");
        }
        Long workspaceId = session.getWorkspaceId();
        Long outDir = session.getOutputDirNodeId();

        SysProduct product = subject.product();
        if (product.getWikiNodeId() == null) {
            Long productDirId = wikiFsService.ensureProductDir(product);
            product.setWikiNodeId(productDirId);
            productService.updateWikiNodeId(product.getId(), productDirId);
        }
        String planVersion = null;
        if (spec.publishRoot() == PipeUnitMaterialSpec.PublishRoot.VER) {
            if (subject.requirement() == null) {
                throw new BizException("缺少需求，无法按版本发布到 Wiki");
            }
            planVersion = materialBinder.resolvePlanVersion(inst, subject.requirement(), product);
            if (!StringUtils.hasText(planVersion)) {
                throw new BizException("缺少计划版本，无法发布到 Wiki");
            }
        }
        Long publishDirId = materialBinder.ensurePublishDir(product, subject, spec, planVersion);

        // 只认标准正式文件名；找不到直接失败
        SysWorkspaceNode formalNode = workspaceFsService.findChildFile(
                workspaceId, outDir, spec.formalOutputName());
        if (formalNode == null) {
            throw new BizException("个人空间尚无正式产出：" + spec.formalOutputName()
                    + "（请重新执行 Agent，确保 write_output 的 display_name 为该标准名）");
        }
        if (!Objects.equals(unit.getOutputWorkspaceNodeId(), formalNode.getId())) {
            unit.setOutputWorkspaceNodeId(formalNode.getId());
        }
        // 发布前确保确认档可见（历史数据可能仍为 hidden）
        revealConfirmOutputs(unit, spec);

        Long wikiId = wikiFsService.publishFromPersonal(workspaceId, formalNode.getId(), publishDirId);
        unit.setOutputWikiNodeId(wikiId);

        // 仅发布最终确认归档；进行中的轮次草稿（…-rN.md）只留个人空间，永不进 Wiki
        if (StringUtils.hasText(spec.confirmOutputName())) {
            SysWorkspaceNode confirm = workspaceFsService.findChildFile(
                    workspaceId, outDir, spec.confirmOutputName());
            if (confirm != null) {
                wikiFsService.publishFromPersonal(workspaceId, confirm.getId(), publishDirId);
            }
        }

        PipeNodeInstance node = nodeInstanceMapper.selectById(unit.getNodeInstanceId());
        markUnitSucceeded(unit, node);
        if (inst != null) {
            inst.setStatus(PipeInstanceStatus.RUNNING.name());
            inst.setWaitingReason(null);
            instanceMapper.updateById(inst);
        }
        enqueueAdvance(inst.getId());
    }

    private void runMergeJob(PipeJob job, PipeInstance inst) {
        String track = "PRD";
        try {
            if (StringUtils.hasText(job.getPayloadJson())) {
                JsonNode n = objectMapper.readTree(job.getPayloadJson());
                track = n.path("track").asText("PRD");
            }
        } catch (Exception ignored) {
        }
        pipeMergeService.runMerge(inst, track);
        instanceMapper.updateById(inst);
    }

    @Transactional
    public void confirmTrackMerge(Long instanceId, String trackCode, String itemsJson) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeInstance inst = requireInstance(instanceId, tenantId);
        PipeMergeService.ConfirmResult result = pipeMergeService.confirm(
                inst, trackCode, itemsJson, TenantSupport.requireUserId());

        PipeUnitInstance revise = unitInstanceMapper.selectOne(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, instanceId)
                .eq(PipeUnitInstance::getUnitCode, result.spec().reviseUnitCode())
                .last("LIMIT 1"));
        if (revise != null) {
            PipeNodeInstance n = nodeInstanceMapper.selectById(revise.getNodeInstanceId());
            if (n != null) {
                n.setStatus("RUNNING");
                n.setEnabledFlag(true);
                if (n.getStartedAt() == null) {
                    n.setStartedAt(LocalDateTime.now());
                }
                nodeInstanceMapper.updateById(n);
            }
            if (result.needRevise()) {
                revise.setStatus("WAITING_TRIGGER");
                revise.setErrorMessage(null);
                unitInstanceMapper.updateById(revise);
                enqueueRunUnit(revise.getId());
            } else {
                // 无有价值项：跳过修订，合并文件仍已落 Wiki，供追溯
                revise.setStatus("SKIPPED");
                revise.setErrorMessage("合并无有价值项，跳过修订");
                revise.setFinishedAt(LocalDateTime.now());
                unitInstanceMapper.updateById(revise);
                enqueueAdvance(instanceId);
            }
        }
        inst.setStatus(PipeInstanceStatus.RUNNING.name());
        inst.setWaitingReason(null);
        instanceMapper.updateById(inst);
    }

    public void advanceInstance(Long instanceId) {
        PipeInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            return;
        }
        // AI 初审齐 → 二审
        if (nodeSucceeded(instanceId, "ai_pre") && nodePendingLike(instanceId, "pm_second")) {
            setNodeStatus(instanceId, "pm_second", "WAITING_HUMAN");
            inst.setStatus(PipeInstanceStatus.WAITING_HUMAN.name());
            inst.setWaitingReason("等待产品经理二审");
            instanceMapper.updateById(inst);
            return;
        }
        // gen_prd 齐 → 可选设计
        if (nodeSucceeded(instanceId, "gen_prd")) {
            openOptionalDesign(inst);
        }
        // 设计齐 → 开评审轨
        if (designReady(inst)) {
            openReviews(inst);
        }
        // 某评审轨齐 → 合并
        maybeOpenTrackMerge(inst, "prd_review", "PRD");
        maybeOpenTrackMerge(inst, "arch_review", "ARCH");
        maybeOpenTrackMerge(inst, "ui_review", "UI");
        maybeOpenTrackMerge(inst, "detail_review", "DETAIL");
        maybeOpenTrackMerge(inst, "cases_review", "CASES");

        // 设计修订齐 → 详设+用例
        if (designReviseReady(inst)) {
            activateNode(instanceId, "gen_detail", true);
            activateNode(instanceId, "gen_cases", true);
        }
        // 详设用例齐 → 评审
        if (nodeSucceeded(instanceId, "gen_detail") && nodeSucceeded(instanceId, "gen_cases")) {
            activateNode(instanceId, "detail_review", true);
            activateNode(instanceId, "cases_review", true);
        }
        // 问题单短流水 ISSUE_AUTO
        if (inst.getIssueId() != null) {
            advanceIssueAuto(inst);
            return;
        }
        // 详设/用例修订齐 → 编码（此处才 worktree）
        if (dtReviseReady(inst) && !nodeSucceeded(instanceId, "gen_coding")
                && !nodeRunningLike(instanceId, "gen_coding")) {
            pipelineService.ensureWorktrees(instanceId);
            activateNode(instanceId, "gen_coding", true);
        }
        if (nodeSucceeded(instanceId, "gen_coding") && findNode(instanceId, "code_review") != null
                && !nodeSucceeded(instanceId, "code_review")
                && !nodeRunningLike(instanceId, "code_review")) {
            pipelineService.ensureWorktrees(instanceId);
            activateNode(instanceId, "code_review", true);
        }
        boolean reviewGate = findNode(instanceId, "code_review") == null
                || nodeSucceeded(instanceId, "code_review");
        if (nodeSucceeded(instanceId, "gen_coding") && reviewGate
                && !nodeSucceeded(instanceId, "gen_int_test")
                && !nodeRunningLike(instanceId, "gen_int_test")) {
            pipelineService.ensureWorktrees(instanceId);
            activateNode(instanceId, "gen_int_test", true);
        }
        if (nodeSucceeded(instanceId, "gen_int_test")) {
            setNodeStatus(instanceId, "done", "SUCCEEDED");
            inst.setStatus(PipeInstanceStatus.DONE.name());
            inst.setFinishedAt(LocalDateTime.now());
            inst.setWaitingReason(null);
            instanceMapper.updateById(inst);
        }
    }

    private void advanceIssueAuto(PipeInstance inst) {
        Long instanceId = inst.getId();
        if (nodeSucceeded(instanceId, "issue_analyze")
                && !nodeSucceeded(instanceId, "issue_fix")
                && !nodeRunningLike(instanceId, "issue_fix")) {
            pipelineService.ensureWorktrees(instanceId);
            activateNode(instanceId, "issue_fix", true);
        }
        if (nodeSucceeded(instanceId, "issue_fix") && findNode(instanceId, "issue_code_review") != null
                && !nodeSucceeded(instanceId, "issue_code_review")
                && !nodeRunningLike(instanceId, "issue_code_review")) {
            pipelineService.ensureWorktrees(instanceId);
            activateNode(instanceId, "issue_code_review", true);
        }
        boolean issueReviewGate = findNode(instanceId, "issue_code_review") == null
                || nodeSucceeded(instanceId, "issue_code_review");
        if (nodeSucceeded(instanceId, "issue_fix") && issueReviewGate
                && !nodeSucceeded(instanceId, "issue_int_test")
                && !nodeRunningLike(instanceId, "issue_int_test")) {
            pipelineService.ensureWorktrees(instanceId);
            activateNode(instanceId, "issue_int_test", true);
        }
        if (nodeSucceeded(instanceId, "issue_int_test")) {
            setNodeStatus(instanceId, "done", "SUCCEEDED");
            inst.setStatus(PipeInstanceStatus.DONE.name());
            inst.setFinishedAt(LocalDateTime.now());
            inst.setWaitingReason(null);
            instanceMapper.updateById(inst);
        }
    }

    private void openOptionalDesign(PipeInstance inst) {
        boolean arch = option(inst, "arch");
        boolean ui = option(inst, "ui");
        markOptional(inst.getId(), "gen_arch", arch);
        markOptional(inst.getId(), "gen_ui", ui);
        markOptional(inst.getId(), "arch_review", arch);
        markOptional(inst.getId(), "ui_review", ui);
        if (arch) {
            activateNode(inst.getId(), "gen_arch", false);
        }
        if (ui) {
            activateNode(inst.getId(), "gen_ui", false);
        }
    }

    private boolean designReady(PipeInstance inst) {
        if (!nodeSucceeded(inst.getId(), "gen_prd")) {
            return false;
        }
        if (option(inst, "arch") && !nodeSucceeded(inst.getId(), "gen_arch") && !nodeSkipped(inst.getId(), "gen_arch")) {
            return false;
        }
        if (option(inst, "ui") && !nodeSucceeded(inst.getId(), "gen_ui") && !nodeSkipped(inst.getId(), "gen_ui")) {
            return false;
        }
        return true;
    }

    private void openReviews(PipeInstance inst) {
        activateNode(inst.getId(), "prd_review", false);
        if (option(inst, "arch")) {
            activateNode(inst.getId(), "arch_review", false);
        }
        if (option(inst, "ui")) {
            activateNode(inst.getId(), "ui_review", false);
        }
    }

    private void maybeOpenTrackMerge(PipeInstance inst, String reviewNode, String track) {
        if (!nodeSucceeded(inst.getId(), reviewNode)) {
            return;
        }
        PipeTrackMerge existing = trackMergeMapper.selectOne(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, inst.getId())
                .eq(PipeTrackMerge::getTrackCode, track)
                .last("LIMIT 1"));
        if (existing != null && List.of("QUEUED", "WAITING_HUMAN", "SUCCEEDED").contains(existing.getStatus())) {
            return;
        }
        if (existing == null) {
            existing = new PipeTrackMerge();
            existing.setInstanceId(inst.getId());
            existing.setTrackCode(track);
            existing.setStatus("QUEUED");
            existing.setCreatedAt(LocalDateTime.now());
            trackMergeMapper.insert(existing);
        } else {
            existing.setStatus("QUEUED");
            trackMergeMapper.updateById(existing);
        }
        PipeJob job = newJob(inst.getId(), null, PipeJobTypes.LLM_MERGE);
        job.setPayloadJson("{\"track\":\"" + track + "\"}");
        jobMapper.insert(job);
    }

    private boolean designReviseReady(PipeInstance inst) {
        if (!trackReviseDone(inst, "PRD", "prd_review", "rev_prd")) {
            return false;
        }
        if (option(inst, "arch") && !trackReviseDone(inst, "ARCH", "arch_review", "rev_arch")) {
            return false;
        }
        if (option(inst, "ui") && !trackReviseDone(inst, "UI", "ui_review", "rev_ui")) {
            return false;
        }
        return true;
    }

    private boolean dtReviseReady(PipeInstance inst) {
        return trackReviseDone(inst, "DETAIL", "detail_review", "rev_detail")
                && trackReviseDone(inst, "CASES", "cases_review", "rev_cases");
    }

    private boolean trackReviseDone(PipeInstance inst, String track, String reviewNode, String unitCode) {
        if (nodeSkipped(inst.getId(), reviewNode)) {
            return true;
        }
        if (!nodeSucceeded(inst.getId(), reviewNode)) {
            return false;
        }
        PipeTrackMerge m = trackMergeMapper.selectOne(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, inst.getId())
                .eq(PipeTrackMerge::getTrackCode, track)
                .last("LIMIT 1"));
        if (m == null || !"SUCCEEDED".equals(m.getStatus())) {
            return false;
        }
        PipeUnitInstance u = unitInstanceMapper.selectOne(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getInstanceId, inst.getId())
                .eq(PipeUnitInstance::getUnitCode, unitCode)
                .last("LIMIT 1"));
        return u != null && ("SUCCEEDED".equals(u.getStatus()) || "SKIPPED".equals(u.getStatus()));
    }

    private boolean trackMergeDone(PipeInstance inst, String track) {
        PipeTrackMerge m = trackMergeMapper.selectOne(new LambdaQueryWrapper<PipeTrackMerge>()
                .eq(PipeTrackMerge::getInstanceId, inst.getId())
                .eq(PipeTrackMerge::getTrackCode, track)
                .last("LIMIT 1"));
        return m != null && "SUCCEEDED".equals(m.getStatus());
    }

    private void activateNode(Long instanceId, String nodeCode, boolean force) {
        PipeNodeInstance n = findNode(instanceId, nodeCode);
        if (n == null) {
            return;
        }
        if (Boolean.FALSE.equals(n.getEnabledFlag()) && !force) {
            return;
        }
        if ("SUCCEEDED".equals(n.getStatus()) || "RUNNING".equals(n.getStatus())
                || "WAITING_HUMAN".equals(n.getStatus())) {
            return;
        }
        n.setStatus("RUNNING");
        n.setEnabledFlag(true);
        if (n.getStartedAt() == null) {
            n.setStartedAt(LocalDateTime.now());
        }
        nodeInstanceMapper.updateById(n);
        List<PipeUnitInstance> units = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getNodeInstanceId, n.getId()));
        for (PipeUnitInstance u : units) {
            if ("PENDING".equals(u.getStatus()) || "WAITING_TRIGGER".equals(u.getStatus())) {
                u.setStatus("WAITING_TRIGGER");
                unitInstanceMapper.updateById(u);
                enqueueRunUnit(u.getId());
            }
        }
    }

    private WbChatSession ensureSession(
            PipeInstance inst, PipeUnitInstance unit, PipeSubject subject, PipeNodeInstance node) {
        return ensureSession(inst, unit, subject, node, null);
    }

    private WbChatSession ensureSession(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeSubject subject,
            PipeNodeInstance node,
            PipeJob job) {
        if (unit.getSessionId() != null) {
            WbChatSession s = sessionMapper.selectById(unit.getSessionId());
            if (s != null) {
                backfillSessionContexts(s, inst, unit, subject);
                return s;
            }
        }
        Long workspaceId = resolvePersonalWorkspaceId(subject);
        WorkspaceSessionCreateRequest req = new WorkspaceSessionCreateRequest();
        req.setSessionType("TASK");
        String ts = LocalDateTime.now().toString().replace('T', ' ');
        if (ts.length() > 16) {
            ts = ts.substring(0, 16);
        }
        req.setTitle("流水线·" + unit.getUnitName() + codingSessionSuffix(job) + "·" + ts);
        req.setRequirementId(subject.linkedRequirementIdOrNull());
        req.setIssueId(subject.issueIdOrNull());
        req.setTaskTypeCode(unit.getTaskTypeCode());
        SysPosition pos = positionService.findByCode(unit.getPositionCode());
        if (pos != null) {
            req.setPositionId(pos.getId());
        } else {
            throw new BizException("岗位不存在：" + unit.getPositionCode());
        }

        PipeUnitMaterialBinder.BindResult bind =
                materialBinder.bindToCreateRequest(req, workspaceId, inst, unit, subject);
        applyRepoBinding(req, inst, unit, subject, node, bind.spec());

        Long sessionId = workspaceSessionService.create(workspaceId, req);
        WbChatSession session = sessionMapper.selectById(sessionId);
        session.setPipeUnitInstanceId(unit.getId());
        sessionMapper.updateById(session);
        unit.setSessionId(sessionId);
        unitInstanceMapper.updateById(unit);
        log.info("pipe session bound unit={} taskType={} jobType={} sessionId={} title={} publishDir={} inputs={} skipOptional={} contexts={}",
                unit.getId(), unit.getTaskTypeCode(),
                job == null ? "-" : job.getJobType(),
                sessionId, req.getTitle(), bind.publishDirId(),
                bind.boundInputNames(), bind.skippedOptionalInputs(),
                req.getContexts() == null ? 0 : req.getContexts().size());
        return session;
    }

    /** 会话标题后缀：任务拆分 / T1 / 汇总，便于前端区分阶段。 */
    private String codingSessionSuffix(PipeJob job) {
        if (job == null || !StringUtils.hasText(job.getJobType())) {
            return "";
        }
        String jt = job.getJobType();
        if (PipeJobTypes.CODING_PLAN.equals(jt)) {
            return "·任务拆分";
        }
        if (PipeJobTypes.CODING_TASK.equals(jt)) {
            String tid = codingTaskId(job);
            return "·" + (StringUtils.hasText(tid) ? tid : "任务");
        }
        if (PipeJobTypes.CODING_INTEGRATE.equals(jt)) {
            return "·汇总";
        }
        if (PipeJobTypes.CODING_ANALYZE.equals(jt)) {
            return "·调度分析";
        }
        return "";
    }

    private Map<String, String> codingMetaForJob(PipeJob job) {
        if (job == null || !PipeJobTypes.isCodingStage(job.getJobType())) {
            return null;
        }
        Map<String, String> meta = new HashMap<>();
        meta.put("codingStage", job.getJobType());
        if (PipeJobTypes.CODING_PLAN.equals(job.getJobType())) {
            meta.put("stageDeliverableName", "coding_plan.json");
            meta.put("taskRemark", "本阶段唯一交付：write_output coding_plan.json；禁止改业务代码与写编码完成报告。");
        } else if (PipeJobTypes.CODING_TASK.equals(job.getJobType())) {
            String tid = codingTaskId(job);
            meta.put("codingTaskId", tid);
            meta.put("stageDeliverableName", "task_result-" + (StringUtils.hasText(tid) ? tid : "unknown") + ".json");
            meta.put("taskRemark", "只实现本任务卡；优先 write_file 出 diff，完成后 write_output "
                    + meta.get("stageDeliverableName") + "；禁止只探不改。");
        } else if (PipeJobTypes.CODING_INTEGRATE.equals(job.getJobType())) {
            meta.put("stageDeliverableName", "编码完成报告.md");
            meta.put("taskRemark", "汇总验收：须确认 schedule.order 每张卡均有 task_result-*.json，再 write_output 编码完成报告.md；缺卡平台会驳回并回队补做。");
        }
        return meta;
    }

    /** 进入下一编码子阶段前清空焦点会话，强制新开窄会话（显式 set null，避免 MP 忽略 null 字段）。 */
    private void clearUnitSessionForNextCodingStage(PipeUnitInstance unit) {
        unit.setSessionId(null);
        unitInstanceMapper.update(null, new LambdaUpdateWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getId, unit.getId())
                .set(PipeUnitInstance::getSessionId, null));
    }

    /** 按材料目录补绑缺失上下文（幂等，不删已有；支持后续新增如 $VER_REQ） */
    private void backfillSessionContexts(
            WbChatSession session, PipeInstance inst, PipeUnitInstance unit, PipeSubject subject) {
        try {
            WorkspaceSessionCreateRequest req = new WorkspaceSessionCreateRequest();
            materialBinder.bindToCreateRequest(req, session.getWorkspaceId(), inst, unit, subject);
            if (req.getContexts() == null || req.getContexts().isEmpty()) {
                return;
            }
            int added = 0;
            for (WorkspaceSessionCreateRequest.SessionContextRef ref : req.getContexts()) {
                if (ref == null || ref.getWikiNodeId() == null) {
                    continue;
                }
                Long cnt = sessionContextMapper.selectCount(new LambdaQueryWrapper<WbSessionContext>()
                        .eq(WbSessionContext::getSessionId, session.getId())
                        .eq(WbSessionContext::getWikiNodeId, ref.getWikiNodeId()));
                if (cnt != null && cnt > 0) {
                    continue;
                }
                SysWikiNode node = wikiFsService.requireNode(ref.getWikiNodeId());
                WbSessionContext row = new WbSessionContext();
                row.setSessionId(session.getId());
                row.setWikiNodeId(node.getId());
                row.setNodeType(node.getNodeType());
                row.setDisplayPath(wikiFsService.buildDisplayPath(node));
                row.setCreatedAt(LocalDateTime.now());
                sessionContextMapper.insert(row);
                added++;
            }
            if (added > 0) {
                log.info("pipe session contexts backfilled sessionId={} unit={} added={}",
                        session.getId(), unit.getId(), added);
            }
        } catch (Exception e) {
            log.warn("pipe session context backfill skipped sessionId={}: {}", session.getId(), e.getMessage());
        }
    }

    private void applyRepoBinding(
            WorkspaceSessionCreateRequest req,
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeSubject subject,
            PipeNodeInstance node,
            PipeUnitMaterialSpec spec) {
        switch (spec.repoRequirement()) {
            case NONE -> {
                if (node != null && CODING_REPO_NODES.contains(node.getNodeCode())) {
                    req.setCodeRepos(pipelineService.listWorktreePaths(inst.getId(), unit.getRepoKind()));
                }
            }
            case BIZ_REQUIRED -> {
                List<String> paths = resolveBizRepoPaths(inst, unit, subject, node, true);
                req.setCodeRepos(paths);
            }
            case BIZ_OPTIONAL -> {
                List<String> paths = resolveBizRepoPaths(inst, unit, subject, node, false);
                if (!paths.isEmpty()) {
                    req.setCodeRepos(paths);
                }
            }
            case TEST_REQUIRED -> {
                List<String> paths = pipelineService.listWorktreePaths(inst.getId(), "TEST");
                if (paths.isEmpty()) {
                    paths = pipelineService.listProductRepoPaths(subject.productId(), "TEST");
                }
                // 问题流水：无测试仓时回退业务仓 worktree / 产品 BIZ 仓
                if (paths.isEmpty() && subject.isIssue()) {
                    paths = pipelineService.listWorktreePaths(inst.getId(), "BIZ");
                    if (paths.isEmpty()) {
                        paths = pipelineService.listProductRepoPaths(subject.productId(), "BIZ");
                    }
                }
                if (paths.isEmpty()) {
                    throw new BizException("集成测试缺少测试/业务仓库本地路径");
                }
                req.setCodeRepos(paths);
            }
        }
    }

    private List<String> resolveBizRepoPaths(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeSubject subject,
            PipeNodeInstance node,
            boolean required) {
        String kind = StringUtils.hasText(unit.getRepoKind()) ? unit.getRepoKind() : "BIZ";
        List<String> paths;
        if (node != null && CODING_REPO_NODES.contains(node.getNodeCode())) {
            paths = pipelineService.listWorktreePaths(inst.getId(), kind);
            if (paths.isEmpty()) {
                paths = pipelineService.listProductRepoPaths(subject.productId(), kind);
            }
        } else {
            paths = pipelineService.listProductRepoPaths(subject.productId(), kind);
        }
        if (paths.isEmpty() && required) {
            throw new BizException("缺少业务代码仓库本地路径（taskType=" + unit.getTaskTypeCode() + "）");
        }
        return paths;
    }

    /**
     * Job Worker 无登录态：优先实例 startedBy，其次需求/问题创建人或处理人。
     */
    private Long resolveActorUserId(PipeInstance inst) {
        Long candidate = inst.getStartedBy();
        if (candidate == null || candidate <= 0) {
            if (inst.getIssueId() != null) {
                IssIssue issue = issueMapper.selectById(inst.getIssueId());
                if (issue != null) {
                    if (issue.getHandlerId() != null && issue.getHandlerId() > 0) {
                        candidate = issue.getHandlerId();
                    } else if (issue.getCreatedBy() != null && issue.getCreatedBy() > 0) {
                        candidate = issue.getCreatedBy();
                    }
                }
            } else if (inst.getRequirementId() != null) {
                ReqRequirement req = requirementMapper.selectById(inst.getRequirementId());
                if (req != null) {
                    if (req.getCreatedBy() != null && req.getCreatedBy() > 0) {
                        candidate = req.getCreatedBy();
                    } else if (req.getCurrentHandlerId() != null && req.getCurrentHandlerId() > 0) {
                        candidate = req.getCurrentHandlerId();
                    }
                }
            }
        }
        if (candidate == null || candidate <= 0) {
            throw new BizException("流水线缺少启动人，无法执行后台任务");
        }
        SysUser user = sysUserMapper.selectById(candidate);
        if (user == null) {
            throw new BizException("流水线启动人无效（userId=" + candidate + "）");
        }
        if (!Objects.equals(inst.getStartedBy(), candidate)) {
            inst.setStartedBy(candidate);
            instanceMapper.updateById(inst);
        }
        return candidate;
    }

    private Long resolvePersonalWorkspaceId(PipeSubject subject) {
        List<TenantWorkspaceVO> list = tenantWorkspaceService.listPersonalSide();
        if (!list.isEmpty()) {
            return list.get(0).getId();
        }
        Long userId = TenantSupport.requireUserId();
        if (sysUserMapper.selectById(userId) == null) {
            throw new BizException("无法创建流水线工作空间：用户不存在 userId=" + userId);
        }
        WorkbenchWorkspaceCreateRequest create = new WorkbenchWorkspaceCreateRequest();
        String raw = subject.refNo() == null ? "pipe" : subject.refNo();
        String safe = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (safe.isBlank()) {
            safe = subject.isIssue() ? "issue" : "req";
        }
        create.setName("pipe_" + safe);
        return tenantWorkspaceService.createPersonal(create);
    }

    private PipeSubject resolveSubject(PipeInstance inst) {
        if (inst.getIssueId() != null) {
            IssIssue issue = issueService.requireOwned(inst.getIssueId());
            SysProduct product = productService.requireOwned(issue.getProductId());
            ReqRequirement linked = null;
            if (issue.getRequirementId() != null) {
                linked = requirementMapper.selectById(issue.getRequirementId());
            }
            return new PipeSubject(product, linked, issue);
        }
        if (inst.getRequirementId() == null) {
            throw new BizException("流水线缺少需求或问题单主体");
        }
        ReqRequirement requirement = requirementMapper.selectById(inst.getRequirementId());
        if (requirement == null || !Objects.equals(requirement.getTenantId(), inst.getTenantId())) {
            throw new BizException("需求不存在");
        }
        SysProduct product = productService.requireOwned(requirement.getProductId());
        return new PipeSubject(product, requirement, null);
    }

    public PipeConfirmRound getPendingConfirm(Long unitId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeUnitInstance unit = requireUnit(unitId, tenantId);
        if (!"WAITING_CONFIRM".equals(unit.getStatus())) {
            return null;
        }
        return confirmRoundMapper.selectOne(new LambdaQueryWrapper<PipeConfirmRound>()
                .eq(PipeConfirmRound::getUnitInstanceId, unitId)
                .eq(PipeConfirmRound::getRoundNo, unit.getConfirmRound())
                .last("LIMIT 1"));
    }

    /** 编码四阶段进度：会话列表 + job + 任务卡（供前端子面板）。 */
    public com.lonple.dap.server.modules.pipeline.dto.CodingProgressVO getCodingProgress(Long unitId) {
        Long tenantId = TenantSupport.requireTenantId();
        PipeUnitInstance unit = requireUnit(unitId, tenantId);
        var vo = new com.lonple.dap.server.modules.pipeline.dto.CodingProgressVO();
        vo.setUnitId(unit.getId());
        vo.setUnitName(unit.getUnitName());
        vo.setUnitStatus(unit.getStatus());
        vo.setCurrentSessionId(unit.getSessionId());
        vo.setErrorMessage(unit.getErrorMessage());

        List<PipeJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<PipeJob>()
                .eq(PipeJob::getUnitInstanceId, unitId)
                .orderByDesc(PipeJob::getId)
                .last("LIMIT 40"));
        String currentStage = null;
        for (PipeJob j : jobs) {
            var jb = new com.lonple.dap.server.modules.pipeline.dto.CodingProgressVO.JobBrief();
            jb.setId(j.getId());
            jb.setJobType(j.getJobType());
            jb.setStatus(j.getStatus());
            jb.setLastError(j.getLastError());
            jb.setPayloadJson(j.getPayloadJson());
            jb.setUpdatedAt(j.getUpdatedAt() == null ? null : j.getUpdatedAt().toString());
            vo.getJobs().add(jb);
            if (currentStage == null && PipeJobTypes.isCodingStage(j.getJobType())
                    && !"CANCELLED".equals(j.getStatus())) {
                currentStage = j.getJobType();
            }
        }
        vo.setCurrentStage(currentStage);

        List<WbChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<WbChatSession>()
                .eq(WbChatSession::getPipeUnitInstanceId, unitId)
                .orderByAsc(WbChatSession::getId));
        for (WbChatSession s : sessions) {
            var ss = new com.lonple.dap.server.modules.pipeline.dto.CodingProgressVO.StageSession();
            ss.setSessionId(s.getId());
            ss.setTitle(s.getTitle());
            ss.setCreatedAt(s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            vo.getSessions().add(ss);
        }

        JsonNode plan = readCodingPlanJson(unit);
        if (plan != null && plan.path("tasks").isArray()) {
            Map<String, Long> taskSessionByTitle = new HashMap<>();
            for (WbChatSession s : sessions) {
                String title = s.getTitle() == null ? "" : s.getTitle();
                for (JsonNode t : plan.path("tasks")) {
                    String tid = t.path("id").asText("");
                    if (StringUtils.hasText(tid) && title.contains("·" + tid + "·")) {
                        taskSessionByTitle.put(tid, s.getId());
                    }
                }
            }
            for (JsonNode t : plan.path("tasks")) {
                var pt = new com.lonple.dap.server.modules.pipeline.dto.CodingProgressVO.PlanTask();
                String tid = t.path("id").asText("");
                pt.setId(tid);
                pt.setTitle(t.path("title").asText(""));
                pt.setModule(t.path("module").asText(""));
                String resultName = "task_result-" + tid + ".json";
                pt.setStatus(readOutputText(unit, resultName) != null ? "DONE" : "PENDING");
                pt.setSessionId(taskSessionByTitle.get(tid));
                vo.getPlanTasks().add(pt);
            }
        }
        return vo;
    }

    private String buildKickoffMessage(
            PipeUnitInstance unit, PipeNodeInstance node, PipeSubject subject, PipeJob job) {
        StringBuilder sb = new StringBuilder();
        boolean codingStage = job != null && PipeJobTypes.isCodingStage(job.getJobType());
        // 编码四阶段：不用「整段开发编码」岗位提示（会诱导逛仓写代码），改用阶段短角色
        if (!codingStage) {
            SysPosition pos = positionService.findByCode(unit.getPositionCode());
            PromptVO prompt = pos == null ? null
                    : promptService.findByPositionAndTaskType(pos.getId(), unit.getTaskTypeCode());
            if (prompt != null && StringUtils.hasText(prompt.getContent())) {
                sb.append("【系统提示词·请严格遵守】\n").append(prompt.getContent()).append("\n\n");
            }
        } else if (PipeJobTypes.CODING_PLAN.equals(job.getJobType())) {
            sb.append("【系统提示词·请严格遵守】\n");
            sb.append("# 角色\n你是编码流水线中的 **任务拆分 Planner**。\n");
            sb.append("# 唯一目标\n写出可执行的任务卡文件 `coding_plan.json`（write_output）。\n");
            sb.append("# 禁止\n禁止改业务代码；禁止写「编码完成报告.md」；禁止为「摸清全仓」而长时间 list_dir。\n");
            sb.append("# 做法\n少量 search_docs/read_docs 读详设要点 + 必要时 search_code/list_dir 看模块边界 → 尽快交卷。\n\n");
        } else if (PipeJobTypes.CODING_TASK.equals(job.getJobType())) {
            sb.append("【系统提示词·请严格遵守】\n");
            sb.append("# 角色\n你是编码流水线中的 **单任务实现 Agent**。\n");
            sb.append("# 目标\n只实现下方任务卡；成功态优先尽快 write_file；完成后 write_output 对应 task_result-*.json。\n");
            sb.append("# 禁止\n不要重写整仓；不要写总报告；改动尽量落在 touch 范围；禁止无目标逛仓、只探不改。\n\n");
        } else if (PipeJobTypes.CODING_INTEGRATE.equals(job.getJobType())) {
            sb.append("【系统提示词·请严格遵守】\n");
            sb.append("# 角色\n你是编码流水线中的 **汇总验收 Agent**。\n");
            sb.append("# 目标\n阅读各 task_result，校验后 write_output「编码完成报告.md」。\n");
            sb.append("# 禁止\n不做大范围业务实现；失败则在报告中点名 taskId。\n\n");
        }
        PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        boolean codingMid = PipeJobTypes.isCodingIntermediate(job.getJobType());
        sb.append("【流水线任务】节点=").append(node == null ? "-" : node.getNodeName())
                .append("；单元=").append(unit.getUnitName())
                .append("；任务类型=").append(unit.getTaskTypeCode());
        if (codingStage) {
            sb.append("；编码阶段=").append(job.getJobType());
        }
        sb.append('\n');
        if (subject.isIssue()) {
            IssIssue issue = subject.issue();
            String taskCode = unit.getTaskTypeCode() == null ? "" : unit.getTaskTypeCode().trim();
            sb.append("问题单编码=").append(issue.getIssueNo()).append('\n');
            if (subject.requirement() != null) {
                sb.append("关联需求=").append(subject.requirement().getReqNo())
                        .append("；").append(subject.requirement().getName()).append('\n');
            }
            if (!codingMid) {
                sb.append("输出文件名=").append(spec.formalOutputName());
                if (isIssueConfirmJsonTask(taskCode)) {
                    // 不点名「*-确认.md」：避免 Agent 误当成自己要 write_output 的交付物
                    sb.append("；有不确定时只在对话输出 need_human_confirm JSON，禁止 write_output 任何确认档（归档由平台生成）");
                } else {
                    sb.append("；确认文件名=").append(spec.confirmOutputName());
                }
                sb.append('\n');
            }
            if ("GEN_ISSUE_FIX".equalsIgnoreCase(taskCode)) {
                sb.append("材料：必填输入为已发布的 Wiki「问题原因分析.md」；问题单.md 可按需查阅；")
                        .append("请先查看会话材料索引（inputs/contexts/模板/规范），用 read_docs 按需读取；");
            } else if ("GEN_ISSUE_ANALYSIS".equalsIgnoreCase(taskCode)) {
                sb.append("材料：必填输入为 Wiki「问题单.md」（含描述/实现步骤/预期结果/已有方案；步骤中的截图为 Markdown 图片链接）；")
                        .append("请先查看会话材料索引（inputs/contexts/模板/规范），用 read_docs 按需读取；");
            } else {
                sb.append("材料：必填输入为 Wiki「问题单.md」（含描述/实现步骤/预期结果/已有方案；步骤中的截图为 Markdown 图片链接）；")
                        .append("请先查看会话材料索引（inputs/contexts/模板/规范），用 read_docs 按需读取；");
            }
            if (!codingMid) {
                sb.append("正式产出用 write_output，display_name 必须为「")
                        .append(spec.formalOutputName()).append("」。");
                if (isIssueConfirmJsonTask(taskCode)) {
                    sb.append("有不确定时只在对话输出 need_human_confirm JSON（findings/openQuestions 用对象数组）。");
                }
                sb.append('\n');
            } else {
                sb.append("本阶段按下方编码阶段说明写出指定 JSON/文件。\n");
            }
            if (StringUtils.hasText(issue.getDescription())) {
                sb.append("\n【问题描述·摘要】\n").append(issue.getDescription()).append('\n');
            }
        } else {
            ReqRequirement requirement = subject.requirement();
            sb.append("需求编号=").append(requirement.getReqNo())
                    .append("；需求名称=").append(requirement.getName()).append('\n');
            if (!codingMid) {
                sb.append("输出文件名=").append(spec.formalOutputName())
                        .append("；确认文件名=").append(spec.confirmOutputName()).append('\n');
            }
            sb.append("材料：请先查看会话材料索引（inputs/contexts/模板/规范），用 read_docs 按需读取；");
            if (!codingMid) {
                sb.append("正式产出用 write_output，display_name 必须为「")
                        .append(spec.formalOutputName()).append("」。\n");
            } else {
                sb.append("本阶段按下方编码阶段说明写出指定 JSON/文件。\n");
            }
            if (StringUtils.hasText(requirement.getDescription())) {
                sb.append("\n【原始需求摘要】\n").append(requirement.getDescription()).append('\n');
            }
        }
        if (PipeJobTypes.CONTINUE_UNIT.equals(job.getJobType())) {
            sb.append("\n【人工确认回灌】\n").append(job.getPayloadJson()).append('\n');
            if (isIntTestTask(unit.getTaskTypeCode())) {
                sb.append("\n【集成测试·按人确认修复】根据回灌：agree=true 的建议必须落实；")
                        .append("agree=false 的项按 comment 原因处理（可不按原建议改）。\n")
                        .append("只 write_file 定点修改；禁止广搜逛仓；不要自己跑全量测试、不要 write_output 报告——")
                        .append("平台会自动复测；仍失败会再分析并等人确认。\n");
            } else {
                sb.append("请根据确认继续：若仍有不确定输出 need_human_confirm JSON；否则继续改码至根因闭环，")
                        .append("再 write_output 正式报告；问题修复报告文末须 **结论：已修复**（未修完写 **结论：未完成**）。\n");
            }
        } else if (job != null && PipeJobTypes.RUN_UNIT.equals(job.getJobType())
                && job.getPayloadJson() != null && job.getPayloadJson().contains("resumeIncompleteFix")) {
            sb.append("\n【续修】").append(job.getPayloadJson()).append('\n');
        } else if (PipeJobTypes.INT_TEST_FIX.equals(job.getJobType())) {
            sb.append("\n【集成测试·修复轮】按人确认的建议 write_file 定点修改；禁止逛仓；")
                    .append("不要自己跑测/写报告，平台随后自动复测。\n");
            if (job.getPayloadJson() != null) {
                sb.append(job.getPayloadJson()).append('\n');
            }
        } else if (PipeJobTypes.INT_TEST_RUN.equals(job.getJobType())) {
            sb.append("\n【集成测试·复测】由平台直接执行存量自动化（本 kickoff 通常不会交给 Agent）。\n");
        } else if (PipeJobTypes.CODING_PLAN.equals(job.getJobType())) {
            sb.append("\n【编码阶段·任务拆分 CODING_PLAN】\n");
            sb.append("根据详设/需求，按「可独立编译验证的模块」拆分任务卡。\n");
            sb.append("禁止大范围改业务代码。用 write_output 写出 coding_plan.json，JSON 结构含 tasks[]：\n");
            sb.append("每项含 id/title/module/goal/touch(数组)/deps(数组)/acceptance(数组)/budget{maxRounds,maxFiles}。\n");
            sb.append("【收窗纪律】未结算 read/list 约 5 次前必须 write_output coding_plan.json；")
                    .append("list_dir 全阶段严格限次；同范围勿反复 read。\n");
            sb.append("本阶段不要写「编码完成报告.md」。不确定时可输出 need_human_confirm JSON。\n");
            sb.append("工具轮次打满将硬失败；宁早交粗糙任务卡，也不要空转逛仓。\n");
        } else if (PipeJobTypes.CODING_TASK.equals(job.getJobType())) {
            sb.append("\n【编码阶段·任务实现 CODING_TASK】\n");
            appendCodingTaskKickoff(sb, unit, job);
            sb.append("只实现本任务卡；改动尽量落在 touch 范围。完成后 write_output 写出 task_result-")
                    .append(codingTaskId(job)).append(".json（改动文件、结论、遗留项），")
                    .append("并可用中文简要说明。不要写「编码完成报告.md」。\n");
            sb.append("【收窗纪律】成功态优先尽快 write_file；未结算探读（read/list）约 5 次前必须 write_file 或 write_output 结算；")
                    .append("write_file 也算结算。禁止无目标逛仓、只探不改；list_dir 已限次。工具轮次打满将硬失败。\n");
        } else if (PipeJobTypes.CODING_INTEGRATE.equals(job.getJobType())) {
            sb.append("\n【编码阶段·汇总 CODING_INTEGRATE】\n");
            sb.append("验收条件：coding_plan.schedule.order（或 executionOrder）中**每一张卡**都已有 ")
                    .append("task_result-{id}.json，**并且** write_output「编码完成报告.md」。")
                    .append("只交报告、缺卡时平台会驳回并回队补做，不算完成。\n");
            sb.append("阅读各 task_result-*.json 与改动摘要做校验；不要大范围改业务实现；")
                    .append("若某任务需返工，在报告中点名 taskId。\n");
            List<String> missingKick = missingCodingTaskResults(unit);
            if (!missingKick.isEmpty()) {
                sb.append("【当前仍缺 task_result】").append(String.join(", ", missingKick)).append('\n');
            }
            appendCodingPlanDigest(sb, unit);
        } else {
            sb.append("\n请按两段式执行：有不确定先只输出确认 JSON（phase=need_human_confirm）；无不确定则直接产出正式结果。\n");
            String taskCode = unit.getTaskTypeCode() == null ? "" : unit.getTaskTypeCode();
            if (taskCode.startsWith("REVIEW_")) {
                sb.append("\n【评审滚动窗】可连读材料以便看清交互；未结算 read 达到约 5 次前必须结算本窗：")
                        .append("write_output 窗口/分段评审（有意见或明确无意见），或输出 need_human_confirm JSON。")
                        .append("无意见也算结算；禁止连续只读不评。同范围勿反复 read；勿短间隔重复 list_dir。")
                        .append("结算后更早原文会被压缩。工具轮次打满将硬失败并中止，不会自动汇总交付物。\n");
            } else if ("GEN_ISSUE_ANALYSIS".equalsIgnoreCase(taskCode)) {
                sb.append("\n【问题分析纪律】先读问题单，少搜早写。不确定时只在对话输出 need_human_confirm JSON，禁止 write_output「*确认*.json」。")
                        .append("未结算探读/搜索约 5 次后系统硬拦探索；必须 write_output「问题原因分析.md」。短预算，禁止广搜 Wiki。\n");
            } else if ("GEN_ISSUE_FIX".equalsIgnoreCase(taskCode)) {
                sb.append("\n【问题修复两阶段】EXPLORE：定点读《问题原因分析》后尽快 write_file；探读/搜索约 5 次未结算则硬拦。")
                        .append("PATCHED（已有 write_file）：禁止 search/list；少量校验 read 后必须 write_output「问题修复报告.md」。")
                        .append("有不确定时只在对话输出 need_human_confirm JSON，禁止 write_output「*确认*」文档。")
                        .append("改完一刀禁止再逛仓；收尾短预算。工具轮次打满硬失败。\n");
            } else if (isIssueStockIntTest(taskCode)) {
                sb.append("\n【存量集成测试】由平台直接跑测并出报告；失败时你只做失败分析建议（确认 JSON），")
                        .append("人同意后再定点 write_file；复测仍由平台执行。禁止空转探仓。\n");
            } else if (isIntTestTask(taskCode)) {
                sb.append("\n【集成测试】执行自动化并 write_output「集成测试报告.md」。")
                        .append("通过写 **结论：通过**；不通过写 **结论：不通过**，由平台/人确认后再修。\n");
            } else if (isDocGenDeliveryTask(taskCode)) {
                sb.append("\n【文档产出滚动窗】先少量检索与针对性阅读，尽早 write_output 落盘大纲/目录或章节草稿，再扩写。")
                        .append("未结算探读/搜索达到约 5 次前必须 write_output 结算；达阈后系统硬拦继续探索。")
                        .append("同范围勿反复 read；勿短间隔重复 list_dir。工具轮次打满将硬失败并中止。\n");
            }
        }
        return sb.toString();
    }

    /** 问题分析/修复：确认必须走对话 JSON，不能 write_output 确认档。 */
    private static boolean isIssueConfirmJsonTask(String taskTypeCode) {
        if (!StringUtils.hasText(taskTypeCode)) {
            return false;
        }
        String code = taskTypeCode.trim().toUpperCase(Locale.ROOT);
        return "GEN_ISSUE_ANALYSIS".equals(code) || "GEN_ISSUE_FIX".equals(code);
    }

    private static boolean isIntTestNode(PipeNodeInstance node) {
        if (node == null || !StringUtils.hasText(node.getNodeCode())) {
            return false;
        }
        String code = node.getNodeCode().trim();
        return "gen_int_test".equals(code) || "issue_int_test".equals(code);
    }

    private static boolean isIntTestTask(String taskTypeCode) {
        if (!StringUtils.hasText(taskTypeCode)) {
            return false;
        }
        String code = taskTypeCode.trim().toUpperCase(Locale.ROOT);
        return "GEN_INT_TEST".equals(code) || "GEN_ISSUE_INT_TEST".equals(code);
    }

    /** 问题流水「存量集成测试」：平台先跑测，禁止 Agent 空转探仓。 */
    private static boolean isIssueStockIntTest(String taskTypeCode) {
        return StringUtils.hasText(taskTypeCode)
                && "GEN_ISSUE_INT_TEST".equalsIgnoreCase(taskTypeCode.trim());
    }

    /**
     * 平台直接执行存量测试：全过 → 写报告并成功；失败 → LLM 分析建议 → 人同意/不同意 → 再修再测。
     */
    private void runIssueStockIntTestPlatformFirst(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeNodeInstance node,
            ReqRequirement requirement,
            WbChatSession session,
            List<PipeRepoWorktree> worktrees,
            PipeJob job) throws Exception {
        unit.setErrorMessage("平台正在执行存量自动化测试…");
        unitInstanceMapper.updateById(unit);
        StockIntTestRunner.Outcome outcome = stockIntTestRunner.run(session.getId(), worktrees);
        persistNamedOutput(unit, "集成测试报告.md", outcome.reportMarkdown(), false);
        if (outcome.passed()) {
            String synthetic = "平台已直接执行存量自动化测试，全部通过。\n\n" + outcome.reportMarkdown();
            handleAgentReply(inst, unit, node, requirement, synthetic, job);
            return;
        }
        analyzeIntTestFailureAndWaitConfirm(
                inst, unit, node, requirement, session, outcome.reportMarkdown());
    }

    /**
     * 人确认后：LLM 按同意项修复；无源码 diff 则禁止复测并回队续修；有 diff 再由平台复测。
     */
    private void runIssueStockIntTestFixThenRetest(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeNodeInstance node,
            ReqRequirement requirement,
            WbChatSession session,
            List<PipeRepoWorktree> worktrees,
            PipeJob job,
            PipeSubject subject) throws Exception {
        int emptyFixAttempt = 0;
        if (job != null && StringUtils.hasText(job.getPayloadJson())) {
            try {
                emptyFixAttempt = objectMapper.readTree(job.getPayloadJson()).path("emptyFixAttempt").asInt(0);
            } catch (Exception ignored) {
                emptyFixAttempt = 0;
            }
        }
        String kickoff = buildKickoffMessage(unit, node, subject, job);
        String lastReport = readSessionOutputContent(session, "集成测试报告.md");
        if (!StringUtils.hasText(lastReport)) {
            int r = unit.getConfirmRound() == null ? 0 : unit.getConfirmRound();
            if (r > 0) {
                lastReport = readSessionOutputContent(session, "集成测试报告-r" + r + ".md");
            }
        }
        if (StringUtils.hasText(lastReport)) {
            String excerpt = lastReport.length() > 3500 ? lastReport.substring(0, 3500) + "…" : lastReport;
            kickoff = kickoff + "\n\n【上一轮平台测试失败报告】\n" + excerpt + "\n";
        }
        kickoff = kickoff
                + "\n【纪律·必须遵守】成功态=至少一次 write_file 改到失败点对应源码；"
                + "禁止只读/搜/跑测不改；禁止 list_dir 逛仓；不要自己跑全量测试、不要 write_output 报告。"
                + "改完用中文简述改动文件即可，平台会检查 git diff 后再复测。\n";
        if (emptyFixAttempt > 0) {
            kickoff = kickoff
                    + "\n【系统驳回】上一轮修复未产生任何源码变更（worktree 干净）。"
                    + "本轮必须 write_file 落到 bms-server（或失败用例指向的模块）src/main/java；"
                    + "只聊天说明不算完成。\n";
        }
        unit.setErrorMessage("按确认结果修复中…");
        unitInstanceMapper.updateById(unit);
        Map<String, String> fixMeta = new LinkedHashMap<>();
        fixMeta.put("codingStage", "INT_TEST_FIX");
        fixMeta.put("taskRemark", "存量集成测试修复：必须 write_file 出 diff；禁止只探不改；无需写报告。");
        agentExecutor.run(session, kickoff, unit.getId(), List.of(), fixMeta);

        if (!anyWorktreeDirty(worktrees)) {
            int nextEmpty = emptyFixAttempt + 1;
            if (nextEmpty >= 3) {
                unit.setStatus("FAILED");
                unit.setErrorMessage("修复轮连续 " + nextEmpty + " 次未产生源码变更（无 write_file/diff），已停止");
                unit.setFinishedAt(LocalDateTime.now());
                unitInstanceMapper.updateById(unit);
                failNode(node, unit.getErrorMessage());
                log.warn("int-test fix empty-diff exhausted unit={} attempts={}", unit.getId(), nextEmpty);
                return;
            }
            releaseCodingSessionQuietly(unit, job);
            clearUnitSessionForNextCodingStage(unit);
            unit.setStatus("RUNNING");
            unit.setFinishedAt(null);
            unit.setErrorMessage("修复轮未改任何源码，已回队强制续修（第 " + nextEmpty + " 次）");
            unitInstanceMapper.updateById(unit);
            PipeJob again = newJob(inst.getId(), unit.getId(), PipeJobTypes.INT_TEST_FIX);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("emptyFixAttempt", nextEmpty);
            payload.put("reason", "no_source_diff");
            if (job != null && StringUtils.hasText(job.getPayloadJson())) {
                try {
                    JsonNode prev = objectMapper.readTree(job.getPayloadJson());
                    if (prev.has("findings") || prev.has("questions")) {
                        payload.set("findings", prev.path("findings"));
                        payload.set("questions", prev.path("questions"));
                    }
                } catch (Exception ignored) {
                    // keep emptyFix only
                }
            }
            again.setPayloadJson(payload.toString());
            jobMapper.insert(again);
            log.warn("int-test fix empty-diff unit={} → requeue INT_TEST_FIX attempt={}",
                    unit.getId(), nextEmpty);
            return;
        }

        try {
            for (PipeRepoWorktree wt : worktrees) {
                if (wt == null || !StringUtils.hasText(wt.getWorktreePath())) {
                    continue;
                }
                gitWorktreeService.commitAllIfDirty(
                        Path.of(wt.getWorktreePath()),
                        "fix(int-test): auto-commit after stock int-test fix");
            }
        } catch (Exception e) {
            log.warn("int-test fix auto-commit failed unit={}: {}", unit.getId(), e.getMessage());
        }

        unit.setErrorMessage("修复完成，平台复测中…");
        unitInstanceMapper.updateById(unit);
        runIssueStockIntTestPlatformFirst(inst, unit, node, requirement, session, worktrees, job);
    }

    private boolean anyWorktreeDirty(List<PipeRepoWorktree> worktrees) {
        if (worktrees == null || worktrees.isEmpty()) {
            return false;
        }
        for (PipeRepoWorktree wt : worktrees) {
            if (wt == null || !StringUtils.hasText(wt.getWorktreePath())) {
                continue;
            }
            try {
                if (gitWorktreeService.isDirty(Path.of(wt.getWorktreePath()))) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("isDirty check failed path={}: {}", wt.getWorktreePath(), e.getMessage());
            }
        }
        return false;
    }

    /**
     * 测挂后：先让 LLM 根据失败报告做原因分析与处理建议，再进入人确认（同意/不同意）。
     */
    private void enterIntTestFailureHumanGate(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeNodeInstance node,
            ReqRequirement requirement,
            String archive,
            IntTestVerdict verdict) throws Exception {
        WbChatSession session = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
        if (session == null) {
            throw new BizException("集成测试失败确认缺少会话");
        }
        analyzeIntTestFailureAndWaitConfirm(inst, unit, node, requirement, session, archive);
    }

    private void analyzeIntTestFailureAndWaitConfirm(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeNodeInstance node,
            ReqRequirement requirement,
            WbChatSession session,
            String archive) throws Exception {
        int nextRound = (unit.getConfirmRound() == null ? 0 : unit.getConfirmRound()) + 1;
        if (unit.getMaxRound() != null && nextRound > unit.getMaxRound()) {
            unit.setStatus("FAILED");
            unit.setErrorMessage("集成测试确认/修复轮次达到上限");
            unit.setFinishedAt(LocalDateTime.now());
            unitInstanceMapper.updateById(unit);
            failNode(node, unit.getErrorMessage());
            return;
        }
        persistNamedOutput(unit, "集成测试报告-r" + nextRound + ".md", archive, false);

        String excerpt = archive == null ? "" : archive.trim();
        if (excerpt.length() > 5000) {
            excerpt = excerpt.substring(0, 5000) + "…";
        }
        unit.setErrorMessage("测试未通过，LLM 分析失败原因中…");
        unitInstanceMapper.updateById(unit);

        String analyzeKickoff = ""
                + "【存量集成测试·失败分析】平台已直接跑完存量自动化且未通过。\n"
                + "请只根据下方测试报告分析失败原因，并给出可执行的处理建议。\n"
                + "禁止 list_dir / search_code / read_file 逛仓；禁止 write_file / run_action / write_output。\n"
                + "只输出 need_human_confirm JSON（不要 Markdown 报告），字段：\n"
                + "- phase: need_human_confirm\n"
                + "- findings: 对象数组，每项含 id、title（失败原因）、suggestion（处理建议，须写清改代码兼容还是改用例，以及改哪里）、recommend（可选）\n"
                + "- openQuestions: []（可空）\n\n"
                + "【平台测试报告】\n"
                + excerpt;

        String reply = agentExecutor.run(session, analyzeKickoff, unit.getId(), List.of(), null);
        JsonNode confirm = agentExecutor.tryParseConfirmJson(reply);
        ArrayNode findingsNode = objectMapper.createArrayNode();
        ArrayNode questionsNode = objectMapper.createArrayNode();
        if (confirm != null && confirm.path("findings").isArray() && !confirm.path("findings").isEmpty()) {
            for (JsonNode f : confirm.path("findings")) {
                findingsNode.add(f);
            }
            if (confirm.path("openQuestions").isArray()) {
                for (JsonNode q : confirm.path("openQuestions")) {
                    questionsNode.add(q);
                }
            }
        } else {
            ObjectNode f1 = findingsNode.addObject();
            f1.put("id", "1");
            f1.put("title", "存量测试未通过（模型未给出结构化分析，请人工判断）");
            String sug = excerpt.length() > 800 ? excerpt.substring(0, 800) + "…" : excerpt;
            f1.put("suggestion", "请选择同意后由研发 Agent 按报告定点修复，或不同意并说明期望（改代码/改用例/其它）。\n" + sug);
        }

        String findingsJson = objectMapper.writeValueAsString(normalizeConfirmFindings(findingsNode));
        String questionsJson = objectMapper.writeValueAsString(normalizeConfirmQuestions(questionsNode));

        unit.setConfirmRound(nextRound);
        unit.setStatus("WAITING_CONFIRM");
        unit.setFinishedAt(null);
        unit.setErrorMessage("测试未通过，等待确认失败分析建议（同意/不同意）r" + nextRound);
        unitInstanceMapper.updateById(unit);

        PipeConfirmRound round = new PipeConfirmRound();
        round.setUnitInstanceId(unit.getId());
        round.setRoundNo(nextRound);
        round.setPhase("need_human_confirm");
        round.setFindingsJson(findingsJson);
        round.setOpenQuestionsJson(questionsJson);
        round.setCreatedAt(LocalDateTime.now());
        PipeConfirmRound existing = confirmRoundMapper.selectOne(new LambdaQueryWrapper<PipeConfirmRound>()
                .eq(PipeConfirmRound::getUnitInstanceId, unit.getId())
                .eq(PipeConfirmRound::getRoundNo, nextRound)
                .last("LIMIT 1"));
        if (existing != null) {
            round.setId(existing.getId());
            confirmRoundMapper.updateById(round);
        } else {
            confirmRoundMapper.insert(round);
        }

        ObjectNode confirmNorm = objectMapper.createObjectNode();
        confirmNorm.put("phase", "need_human_confirm");
        confirmNorm.set("findings", objectMapper.readTree(findingsJson));
        confirmNorm.set("openQuestions", objectMapper.readTree(questionsJson));
        archiveConfirmDraft(unit, node, requirement, round, confirmNorm);
        refreshNodeHumanWait(node, unit.getId(),
                unit.getUnitName() + " 等待确认失败分析 r" + nextRound);
        if (inst != null) {
            inst.setStatus(PipeInstanceStatus.WAITING_HUMAN.name());
            inst.setWaitingReason(unit.getUnitName() + " 等待确认失败分析建议");
            instanceMapper.updateById(inst);
        }
        log.info("int-test analyze gate unit={} round={} findings={}",
                unit.getId(), nextRound, normalizeConfirmFindings(findingsNode).size());
    }

    private String readSessionOutputContent(WbChatSession session, String fileName) {
        if (session == null || session.getOutputDirNodeId() == null || !StringUtils.hasText(fileName)) {
            return "";
        }
        try {
            SysWorkspaceNode file = workspaceFsService.findChildFile(
                    session.getWorkspaceId(), session.getOutputDirNodeId(), fileName);
            if (file == null) {
                return "";
            }
            var vo = workspaceFsService.readFile(session.getWorkspaceId(), file.getId());
            return vo == null || vo.getContent() == null ? "" : vo.getContent();
        } catch (Exception e) {
            log.warn("readSessionOutputContent failed session={} file={}: {}",
                    session.getId(), fileName, e.getMessage());
            return "";
        }
    }

    private void releaseCodingSessionQuietly(PipeUnitInstance unit, PipeJob job) {
        if (unit == null || unit.getSessionId() == null) {
            return;
        }
        try {
            sandboxPipelineSupport.releaseForCoding(
                    unit.getSessionId(), job == null ? null : job.getId());
        } catch (Exception e) {
            log.warn("releaseCodingSessionQuietly failed unit={}: {}", unit.getId(), e.getMessage());
        }
    }

    /** 文件是否在本会话创建之后被写入/更新（用于区分历史残留与本轮违规写出）。 */
    private static boolean touchedInSession(SysWorkspaceNode file, WbChatSession session) {
        if (file == null || session == null || session.getCreatedAt() == null) {
            return false;
        }
        LocalDateTime touched = file.getUpdatedAt() != null ? file.getUpdatedAt() : file.getCreatedAt();
        return touched != null && !touched.isBefore(session.getCreatedAt());
    }

    /** 重跑前删除个人空间中的确认档（含 -rN），避免误判。 */
    private void purgeIssueConfirmOutputs(PipeUnitInstance unit) {
        try {
            PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
            if (spec.confirmOutputName() == null || spec.confirmOutputName().isBlank()) {
                return;
            }
            WbChatSession session = resolveUnitOutputSession(unit);
            if (session == null || session.getOutputDirNodeId() == null) {
                return;
            }
            Long ws = session.getWorkspaceId();
            Long dir = session.getOutputDirNodeId();
            List<String> names = new ArrayList<>();
            names.add(spec.confirmOutputName());
            for (int r = 1; r <= 32; r++) {
                names.add(spec.confirmOutputName().replace(".md", "") + "-r" + r + ".md");
            }
            for (String name : names) {
                SysWorkspaceNode n = workspaceFsService.findChildFile(ws, dir, name);
                if (n != null) {
                    workspaceFsService.deleteNode(ws, n.getId());
                    log.info("purged stale confirm output unit={} file={}", unit.getId(), name);
                }
            }
        } catch (Exception e) {
            log.warn("purge confirm outputs failed unit={}: {}", unit.getId(), e.getMessage());
        }
    }

    /** 文档类 GEN：与评审一样走交付收窗纪律（不含编码/联调/问题修复）。 */
    private static boolean isDocGenDeliveryTask(String taskTypeCode) {
        if (!StringUtils.hasText(taskTypeCode)) {
            return false;
        }
        String code = taskTypeCode.trim().toUpperCase();
        if (!code.startsWith("GEN_")) {
            return false;
        }
        return !(code.equals("GEN_CODING")
                || code.equals("GEN_ISSUE_FIX")
                || code.equals("GEN_INT_TEST")
                || code.equals("GEN_ISSUE_INT_TEST"));
    }

    private void persistOutputIfPossible(
            PipeUnitInstance unit, PipeNodeInstance node, ReqRequirement requirement, String reply) {
        if (!StringUtils.hasText(reply) || agentExecutor.tryParseConfirmJson(reply) != null) {
            return;
        }
        PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        WbChatSession session = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
        if (session == null || session.getOutputDirNodeId() == null) {
            log.warn("persist skip: no session output dir unit={}", unit.getId());
            return;
        }
        Long workspaceId = session.getWorkspaceId();
        Long outDir = session.getOutputDirNodeId();
        SysWorkspaceNode existing = workspaceFsService.findChildFile(
                workspaceId, outDir, spec.formalOutputName());

        // Agent 规范：正文用 write_output 落盘，聊天只写摘要。绝不能用摘要覆盖已写出的正式文档。
        if (existing != null) {
            String existingBody = null;
            try {
                existingBody = workspaceFsService.readFileForTenant(workspaceId, existing.getId()).getContent();
            } catch (Exception ignored) {
            }
            if (looksLikeFormalDocument(existingBody) || !looksLikeFormalDocument(reply)) {
                unit.setOutputWorkspaceNodeId(existing.getId());
                unitInstanceMapper.updateById(unit);
                revealConfirmOutputs(unit, spec);
                if (!looksLikeFormalDocument(reply) && looksLikeFormalDocument(existingBody)) {
                    log.info("persist skip chat reply; keep write_output file unit={} file={}",
                            unit.getId(), spec.formalOutputName());
                }
                return;
            }
        }
        if (!looksLikeFormalDocument(reply)) {
            log.warn("persist skip: reply is not formal doc unit={} file={} replyChars={}",
                    unit.getId(), spec.formalOutputName(), reply.length());
            if (existing != null) {
                unit.setOutputWorkspaceNodeId(existing.getId());
                unitInstanceMapper.updateById(unit);
            }
            revealConfirmOutputs(unit, spec);
            return;
        }
        Long wsNodeId = persistNamedOutput(unit, spec.formalOutputName(), reply, false);
        if (wsNodeId == null && existing != null) {
            wsNodeId = existing.getId();
        }
        if (wsNodeId != null) {
            unit.setOutputWorkspaceNodeId(wsNodeId);
            unitInstanceMapper.updateById(unit);
        }
        revealConfirmOutputs(unit, spec);
    }

    /**
     * 判断是否像正式交付文档（Markdown），而非「已写入…本轮说明」类聊天摘要。
     * 评审书通常短于 PRD，门槛放宽到 200 字并以 # 开头即可。
     */
    private static boolean looksLikeFormalDocument(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String t = content.trim();
        if (t.length() < 200) {
            return false;
        }
        if (!t.startsWith("#")) {
            return false;
        }
        // 典型工作台摘要：短说明 + 已写入路径，不是模板正文
        if (t.contains("**本轮说明**") || t.contains("**已写入文件**")) {
            return false;
        }
        if (t.contains("已写入：") && t.length() < 1200 && !t.contains("## ")) {
            return false;
        }
        return true;
    }

    /**
     * 写入个人空间输出目录。确认归档草稿 {@code hidden=true}，完成输出前不出现在目录树。
     */
    private Long persistNamedOutput(PipeUnitInstance unit, String fileName, String content, boolean hidden) {
        try {
            WbChatSession session = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
            if (session == null || session.getOutputDirNodeId() == null) {
                log.warn("persist skip: no session output dir unit={}", unit.getId());
                return null;
            }
            return workspaceFsService.upsertTextFile(
                    session.getWorkspaceId(),
                    session.getOutputDirNodeId(),
                    fileName,
                    content,
                    hidden);
        } catch (Exception e) {
            log.warn("persist pipe output to personal failed: {}", e.getMessage());
            return null;
        }
    }

    private void revealConfirmOutputs(PipeUnitInstance unit, PipeUnitMaterialSpec spec) {
        try {
            WbChatSession session = unit.getSessionId() == null ? null : sessionMapper.selectById(unit.getSessionId());
            if (session == null || session.getOutputDirNodeId() == null) {
                return;
            }
            List<String> names = new ArrayList<>();
            if (StringUtils.hasText(spec.confirmOutputName())) {
                names.add(spec.confirmOutputName());
            }
            int rounds = unit.getConfirmRound() == null ? 0 : unit.getConfirmRound();
            if (StringUtils.hasText(spec.confirmOutputName())) {
                for (int r = 1; r <= rounds; r++) {
                    names.add(spec.confirmOutputName().replace(".md", "") + "-r" + r + ".md");
                }
            }
            workspaceFsService.unhideFiles(session.getWorkspaceId(), session.getOutputDirNodeId(), names);
        } catch (Exception e) {
            log.warn("reveal confirm outputs failed: {}", e.getMessage());
        }
    }

    private void archiveConfirmDraft(
            PipeUnitInstance unit, PipeNodeInstance node, ReqRequirement requirement,
            PipeConfirmRound round, JsonNode confirm) {
        PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        String doc = buildConfirmMarkdown(
                unit.getUnitName() + " 确认 r" + round.getRoundNo(),
                confirm.path("findings"),
                confirm.path("openQuestions"),
                null);
        // 轮次草稿仅个人空间（hidden，且永不发布 Wiki）；汇总确认文件同名覆盖
        String roundName = spec.confirmOutputName().replace(".md", "")
                + "-r" + round.getRoundNo() + ".md";
        Long nodeId = persistNamedOutput(unit, roundName, doc, true);
        persistNamedOutput(unit, spec.confirmOutputName(), doc, true);
        if (nodeId != null) {
            round.setWikiNodeId(nodeId);
            confirmRoundMapper.updateById(round);
        }
    }

    private void archiveConfirm(PipeUnitInstance unit, PipeConfirmRound round) {
        PipeUnitMaterialSpec spec = PipeUnitMaterialCatalog.require(unit.getTaskTypeCode());
        JsonNode findings = readJsonNode(round.getFindingsJson());
        JsonNode questions = readJsonNode(round.getOpenQuestionsJson());
        JsonNode answers = readJsonNode(round.getHumanAnswersJson());
        String doc = buildConfirmMarkdown(
                spec.confirmOutputName().replace(".md", "") + " r" + round.getRoundNo(),
                findings,
                questions,
                answers);
        // 人确认后仍 hidden，等正式文档完成输出再显示
        Long nodeId = persistNamedOutput(unit, spec.confirmOutputName(), doc, true);
        if (nodeId != null) {
            round.setWikiNodeId(nodeId);
            confirmRoundMapper.updateById(round);
        }
    }

    /** 确认归档：可读 Markdown 表格，不要整篇裸 JSON */
    private String buildConfirmMarkdown(String title, JsonNode findings, JsonNode questions, JsonNode humanAnswers) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        sb.append("## findings（一类）\n\n");
        sb.append("| ID | 问题 | 严重级别 | 建议/结论 | 推荐 |\n|---|---|---|---|---|\n");
        if (findings != null && findings.isArray() && !findings.isEmpty()) {
            for (JsonNode f : findings) {
                String findingTitle = firstText(f, "title", "issue", "summary", "content");
                String advice = joinSuggestions(f.path("suggestions"));
                if ("—".equals(advice)) {
                    advice = firstText(f, "conclusion", "suggestion", "impact");
                    if (!StringUtils.hasText(advice)) {
                        advice = "—";
                    }
                }
                sb.append("| ").append(cell(f, "id"))
                        .append(" | ").append(escapeCell(findingTitle))
                        .append(" | ").append(cell(f, "severity"))
                        .append(" | ").append(escapeCell(advice))
                        .append(" | ").append(cell(f, "recommend"))
                        .append(" |\n");
            }
        } else {
            sb.append("| — | 无 | — | — | — |\n");
        }

        sb.append("\n## openQuestions（二类）\n\n");
        sb.append("| ID | 问题 | 提示 |\n|---|---|---|\n");
        if (questions != null && questions.isArray() && !questions.isEmpty()) {
            for (JsonNode q : questions) {
                String qText = firstText(q, "question", "title");
                sb.append("| ").append(cell(q, "id"))
                        .append(" | ").append(escapeCell(qText))
                        .append(" | ").append(cell(q, "hint"))
                        .append(" |\n");
            }
        } else {
            sb.append("| — | 无 | — |\n");
        }

        if (humanAnswers != null && !humanAnswers.isNull() && !humanAnswers.isMissingNode()) {
            sb.append("\n## 人工确认\n\n");
            JsonNode findingAnswers = humanAnswers.path("findings");
            if (findingAnswers.isMissingNode()) {
                findingAnswers = humanAnswers.path("findingAnswers");
            }
            sb.append("### findings 处置\n\n");
            sb.append("| ID | 同意 | 意见 |\n|---|---|---|\n");
            if (findingAnswers.isArray() && !findingAnswers.isEmpty()) {
                for (JsonNode a : findingAnswers) {
                    String agree = a.path("agree").isMissingNode()
                            ? String.valueOf(a.path("decision").asText(""))
                            : String.valueOf(a.path("agree").asBoolean());
                    sb.append("| ").append(cell(a, "id"))
                            .append(" | ").append(escapeCell(agree))
                            .append(" | ").append(firstText(a, "comment", "opinion"))
                            .append(" |\n");
                }
            } else {
                sb.append("| — | — | 尚未确认或无 findings |\n");
            }
            JsonNode qAnswers = humanAnswers.path("questions");
            if (qAnswers.isMissingNode()) {
                qAnswers = humanAnswers.path("questionAnswers");
            }
            sb.append("\n### openQuestions 答复\n\n");
            sb.append("| ID | 答复 |\n|---|---|\n");
            if (qAnswers.isArray() && !qAnswers.isEmpty()) {
                for (JsonNode a : qAnswers) {
                    sb.append("| ").append(cell(a, "id"))
                            .append(" | ").append(firstText(a, "answer", "comment"))
                            .append(" |\n");
                }
            } else {
                sb.append("| — | 尚未确认或无开放问题 |\n");
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private JsonNode readJsonNode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.missingNode();
        }
    }

    private static String cell(JsonNode n, String field) {
        if (n == null) {
            return "";
        }
        if (n.isTextual() && "id".equals(field)) {
            return "—";
        }
        return escapeCell(n.path(field).asText(""));
    }

    private static String firstText(JsonNode n, String... fields) {
        if (n == null) {
            return "";
        }
        if (n.isTextual()) {
            return escapeCell(n.asText(""));
        }
        for (String f : fields) {
            String v = n.path(f).asText("");
            if (StringUtils.hasText(v)) {
                return escapeCell(v);
            }
        }
        return "";
    }

    /** findings：string → {id,title}；已是对象则补 id。 */
    private List<Map<String, Object>> normalizeConfirmFindings(JsonNode findings) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (findings == null || !findings.isArray()) {
            return out;
        }
        int i = 1;
        for (JsonNode f : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (f != null && f.isTextual()) {
                row.put("id", String.valueOf(i));
                row.put("title", f.asText("").trim());
            } else if (f != null && f.isObject()) {
                String id = f.path("id").asText("");
                row.put("id", StringUtils.hasText(id) ? id : String.valueOf(i));
                String title = firstRawText(f, "title", "issue", "summary", "content", "description");
                row.put("title", StringUtils.hasText(title) ? title : ("发现项 " + i));
                if (f.hasNonNull("severity")) {
                    row.put("severity", f.path("severity").asText(""));
                }
                if (f.has("suggestion") || f.has("suggestions") || f.has("conclusion")) {
                    String advice = firstRawText(f, "suggestion", "conclusion");
                    if (!StringUtils.hasText(advice) && f.path("suggestions").isArray()) {
                        advice = joinSuggestions(f.path("suggestions"));
                        if ("—".equals(advice)) {
                            advice = "";
                        }
                    }
                    if (StringUtils.hasText(advice)) {
                        row.put("suggestion", advice);
                    }
                }
            } else {
                i++;
                continue;
            }
            out.add(row);
            i++;
        }
        return out;
    }

    /** openQuestions：string → {id,question}。 */
    private List<Map<String, Object>> normalizeConfirmQuestions(JsonNode questions) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (questions == null || !questions.isArray()) {
            return out;
        }
        int i = 1;
        for (JsonNode q : questions) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (q != null && q.isTextual()) {
                row.put("id", String.valueOf(i));
                row.put("question", q.asText("").trim());
            } else if (q != null && q.isObject()) {
                String id = q.path("id").asText("");
                row.put("id", StringUtils.hasText(id) ? id : String.valueOf(i));
                String text = firstRawText(q, "question", "text", "title");
                row.put("question", StringUtils.hasText(text) ? text : ("问题 " + i));
            } else {
                i++;
                continue;
            }
            out.add(row);
            i++;
        }
        return out;
    }

    private static String firstRawText(JsonNode n, String... fields) {
        if (n == null) {
            return "";
        }
        if (n.isTextual()) {
            return n.asText("");
        }
        for (String f : fields) {
            String v = n.path(f).asText("");
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return "";
    }

    private static String joinSuggestions(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode s : arr) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(s.asText(""));
        }
        return escapeCell(sb.toString());
    }

    private static String escapeCell(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "—";
        }
        return raw.replace("|", "\\|").replace("\n", " ").trim();
    }

    /** 正式产出已写入个人空间，等待人工发布到 Wiki */
    private void markUnitOutputReady(PipeUnitInstance unit, PipeNodeInstance node, PipeInstance inst) {
        unit.setStatus("WAITING_PUBLISH");
        unit.setErrorMessage(null);
        unitInstanceMapper.updateById(unit);
        refreshNodeHumanWait(node, unit.getId(), unit.getUnitName() + " 待发布文档到 Wiki");
    }

    /**
     * 多岗并行：若同节点仍有 RUNNING 单元，节点保持 RUNNING、实例不堵死；
     * 否则节点/实例进入 WAITING_HUMAN。
     */
    private void refreshNodeHumanWait(PipeNodeInstance node, Long pausingUnitId, String reason) {
        if (node == null) {
            return;
        }
        List<PipeUnitInstance> siblings = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getNodeInstanceId, node.getId()));
        boolean anyRunning = siblings.stream().anyMatch(u ->
                "RUNNING".equals(u.getStatus()) && !Objects.equals(u.getId(), pausingUnitId));
        if (anyRunning) {
            node.setStatus("RUNNING");
            nodeInstanceMapper.updateById(node);
            PipeInstance inst = instanceMapper.selectById(node.getInstanceId());
            if (inst != null && !PipeInstanceStatus.CANCELLED.name().equals(inst.getStatus())
                    && !PipeInstanceStatus.FAILED.name().equals(inst.getStatus())) {
                inst.setStatus(PipeInstanceStatus.RUNNING.name());
                // 保留提示，但不阻塞其他单元 Job
                inst.setWaitingReason(reason + "（同节点其他单元并行中）");
                instanceMapper.updateById(inst);
            }
            return;
        }
        node.setStatus("WAITING_HUMAN");
        nodeInstanceMapper.updateById(node);
        PipeInstance inst = instanceMapper.selectById(node.getInstanceId());
        if (inst != null) {
            inst.setStatus(PipeInstanceStatus.WAITING_HUMAN.name());
            inst.setWaitingReason(reason);
            instanceMapper.updateById(inst);
        }
    }

    private static boolean isCodeReview(String taskTypeCode) {
        return taskTypeCode != null && CODE_REVIEW_TASKS.contains(taskTypeCode.trim().toUpperCase(Locale.ROOT));
    }

    private void runCodeReviewRound(
            PipeInstance inst, PipeUnitInstance unit, PipeNodeInstance node, PipeJob job) throws Exception {
        int nextRound = (unit.getConfirmRound() == null ? 0 : unit.getConfirmRound()) + 1;
        if (unit.getMaxRound() != null && nextRound > unit.getMaxRound()) {
            // 触顶软放行：不再 FAILED，避免 nit 循环把整条流水线打挂
            String soft = "# 代码评审报告\n\n"
                    + "轮次已达上限 " + unit.getMaxRound() + "，按策略放行（遗留项视为接受）。\n";
            persistNamedOutput(unit, "代码评审报告.md", soft, false);
            unit.setErrorMessage(null);
            markUnitSucceeded(unit, node);
            if (inst != null) {
                inst.setStatus(PipeInstanceStatus.RUNNING.name());
                inst.setWaitingReason(null);
                instanceMapper.updateById(inst);
                enqueueAdvance(inst.getId());
            }
            return;
        }
        PipeRepoWorktree biz = bizWorktree(inst.getId());
        Path workTree = Path.of(biz.getWorktreePath());
        // 评审前兜底入库，避免 Agent 只 write_file 未 commit 导致空 diff
        try {
            gitWorktreeService.commitAllIfDirty(workTree,
                    "chore(pipe): auto-commit before code review r" + nextRound);
        } catch (Exception e) {
            log.warn("pre-review commit failed unit={}: {}", unit.getId(), e.getMessage());
            throw e;
        }
        String preFixHead = readPreFixHead(job);
        boolean verifyMode = nextRound >= 2;
        String base;
        if (verifyMode && StringUtils.hasText(preFixHead)) {
            // 复评只看修复引入的 diff（上一轮 HEAD ... 当前 HEAD）
            base = preFixHead;
        } else {
            base = gitWorktreeService.reviewBaseBranch(Path.of(biz.getBaseLocalPath()));
        }
        String head = biz.getBranchName();
        log.info("code review diff unit={} round={} verify={} base={} head={} worktree={}",
                unit.getId(), nextRound, verifyMode, base, head, workTree);
        ReviewResult result = reviewOrchestrator.reviewWorktree(
                "pipe-" + unit.getId() + "-r" + nextRound, workTree, base, head);
        List<Map<String, Object>> findings = new ArrayList<>();
        int i = 1;
        int droppedLow = 0;
        for (ReviewIssue issue : result.getIssues() == null ? List.<ReviewIssue>of() : result.getIssues()) {
            String severity = issue.getSeverity() == null ? "" : issue.getSeverity().trim();
            // 复评收敛：只保留 HIGH/MEDIUM，丢掉对新写法继续抠的 LOW
            if (verifyMode && isLowSeverity(severity)) {
                droppedLow++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            String id = issue.getId() == null || issue.getId().isBlank() ? "R" + i : issue.getId();
            row.put("id", id);
            String title = issue.getTitle() == null ? "评审问题 " + i : issue.getTitle();
            ReviewIssue.Location loc = issue.getLocation();
            if (loc != null && loc.getPath() != null) {
                title = title + " (" + loc.getPath()
                        + (loc.getStartLine() == null ? "" : ":" + loc.getStartLine()) + ")";
            }
            row.put("title", title);
            row.put("severity", issue.getSeverity());
            String suggestion = issue.getSuggestion();
            if (suggestion == null || suggestion.isBlank()) {
                suggestion = issue.getDescription();
            }
            row.put("suggestion", suggestion);
            row.put("description", issue.getDescription());
            findings.add(row);
            i++;
        }
        if (verifyMode && droppedLow > 0) {
            log.info("code review verify drop LOW unit={} round={} dropped={}",
                    unit.getId(), nextRound, droppedLow);
        }
        unit.setConfirmRound(nextRound);
        unit.setStatus("WAITING_CONFIRM");
        unitInstanceMapper.updateById(unit);

        PipeConfirmRound round = new PipeConfirmRound();
        round.setUnitInstanceId(unit.getId());
        round.setRoundNo(nextRound);
        round.setPhase("need_human_confirm");
        round.setFindingsJson(objectMapper.writeValueAsString(findings));
        round.setOpenQuestionsJson("[]");
        round.setCreatedAt(LocalDateTime.now());
        PipeConfirmRound existing = confirmRoundMapper.selectOne(new LambdaQueryWrapper<PipeConfirmRound>()
                .eq(PipeConfirmRound::getUnitInstanceId, unit.getId())
                .eq(PipeConfirmRound::getRoundNo, nextRound)
                .last("LIMIT 1"));
        if (existing != null) {
            round.setId(existing.getId());
            confirmRoundMapper.updateById(round);
        } else {
            confirmRoundMapper.insert(round);
        }
        String reason;
        if (findings.isEmpty()) {
            reason = verifyMode
                    ? unit.getUnitName() + " 复评无高/中问题，请确认后继续 r" + nextRound
                    : unit.getUnitName() + " 评审无问题，请确认后继续 r" + nextRound;
        } else {
            reason = unit.getUnitName() + " 等待确认是否修改 r" + nextRound;
        }
        refreshNodeHumanWait(node, unit.getId(), reason);
    }

    private void continueCodeReview(
            PipeInstance inst, PipeUnitInstance unit, PipeNodeInstance node, PipeJob job) throws Exception {
        JsonNode answers = objectMapper.readTree(job.getPayloadJson() == null ? "{}" : job.getPayloadJson());
        JsonNode decisions = answers.path("findings");
        PipeConfirmRound round = confirmRoundMapper.selectOne(new LambdaQueryWrapper<PipeConfirmRound>()
                .eq(PipeConfirmRound::getUnitInstanceId, unit.getId())
                .eq(PipeConfirmRound::getRoundNo, unit.getConfirmRound())
                .last("LIMIT 1"));
        JsonNode findings = round == null ? null : readJsonNode(round.getFindingsJson());
        String report = buildCodeReviewReport(unit.getConfirmRound(), findings, decisions);
        persistNamedOutput(unit, "代码评审报告.md", report, false);
        unit.setOutputWorkspaceNodeId(null);
        revealConfirmOutputs(unit, PipeUnitMaterialCatalog.require(unit.getTaskTypeCode()));

        StringBuilder fix = new StringBuilder();
        StringBuilder skip = new StringBuilder();
        boolean fixHighOrMedium = false;
        if (decisions.isArray()) {
            for (JsonNode d : decisions) {
                String id = d.path("id").asText("");
                String comment = d.path("comment").asText("");
                JsonNode src = findFinding(findings, id);
                String title = src == null ? id : src.path("title").asText(id);
                String suggestion = src == null ? "" : src.path("suggestion").asText("");
                String severity = src == null ? "" : src.path("severity").asText("");
                if (d.path("agree").asBoolean(false)) {
                    fix.append("- ").append(id).append(' ').append(title).append('\n');
                    if (!suggestion.isBlank()) {
                        fix.append("  建议：").append(suggestion).append('\n');
                    }
                    // 显式 LOW 不触发复评；HIGH/MEDIUM/未标级别均复评
                    if (!isLowSeverity(severity)) {
                        fixHighOrMedium = true;
                    }
                } else {
                    skip.append("- ").append(id).append(' ').append(title)
                            .append("\n  不修改原因：").append(comment).append('\n');
                }
            }
        }
        PipeRepoWorktree biz = bizWorktree(inst.getId());
        Path workTree = Path.of(biz.getWorktreePath());
        Files.writeString(workTree.resolve(".dap-review-skip.md"), skip.toString());
        if (!fix.isEmpty()) {
            unit.setStatus("RUNNING");
            unitInstanceMapper.updateById(unit);
            if (node != null) {
                node.setStatus("RUNNING");
                nodeInstanceMapper.updateById(node);
            }
            // 修复前 HEAD：复评时作为 base，只看 fix commit 区间
            String preFixHead = gitWorktreeService.revParse(workTree, "HEAD");
            ObjectNode fixPayload = objectMapper.createObjectNode();
            fixPayload.put("preFixHead", preFixHead);
            fixPayload.put("fixText", fix.toString());
            // 仅 LOW 修改 → 不复评；含 HIGH/MEDIUM → 窄范围复评
            fixPayload.put("reReview", fixHighOrMedium);
            PipeJob fixJob = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODE_REVIEW_FIX);
            fixJob.setPayloadJson(objectMapper.writeValueAsString(fixPayload));
            jobMapper.insert(fixJob);
            return;
        }
        markUnitSucceeded(unit, node);
        if (inst != null) {
            inst.setStatus(PipeInstanceStatus.RUNNING.name());
            inst.setWaitingReason(null);
            instanceMapper.updateById(inst);
        }
        enqueueAdvance(inst.getId());
    }

    private static boolean isLowSeverity(String severity) {
        return severity != null && "LOW".equalsIgnoreCase(severity.trim());
    }

    private JsonNode readCodeReviewFixPayload(String raw) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        String text = raw.trim();
        if (!text.startsWith("{")) {
            ObjectNode legacy = objectMapper.createObjectNode();
            legacy.put("fixText", raw);
            legacy.put("reReview", true);
            return legacy;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
        } catch (Exception e) {
            ObjectNode legacy = objectMapper.createObjectNode();
            legacy.put("fixText", raw);
            legacy.put("reReview", true);
            return legacy;
        }
    }

    private String readPreFixHead(PipeJob job) {
        if (job == null || !StringUtils.hasText(job.getPayloadJson())) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(job.getPayloadJson());
            return node.path("preFixHead").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private PipeRepoWorktree bizWorktree(Long instanceId) {
        List<PipeRepoWorktree> trees = pipelineService.ensureWorktrees(instanceId);
        for (PipeRepoWorktree w : trees) {
            if (w.getRepoKind() == null || "BIZ".equalsIgnoreCase(w.getRepoKind())
                    || "BOTH".equalsIgnoreCase(w.getRepoKind())) {
                return w;
            }
        }
        if (!trees.isEmpty()) {
            return trees.get(0);
        }
        throw new BizException("代码评审缺少业务仓 worktree");
    }

    private static JsonNode findFinding(JsonNode findings, String id) {
        if (findings == null || !findings.isArray()) {
            return null;
        }
        for (JsonNode f : findings) {
            if (id.equals(f.path("id").asText())) {
                return f;
            }
        }
        return null;
    }

    private static String buildCodeReviewReport(Integer roundNo, JsonNode findings, JsonNode decisions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 代码评审报告\n\n");
        sb.append("轮次：").append(roundNo == null ? 1 : roundNo).append("\n\n");
        sb.append("| ID | 问题 | 严重级别 | 处置 | 说明 |\n|---|---|---|---|---|\n");
        if (findings == null || !findings.isArray() || findings.isEmpty()) {
            sb.append("| — | 本轮未发现问题 | — | 确认通过 | — |\n");
            return sb.toString();
        }
        for (JsonNode f : findings) {
            String id = f.path("id").asText("");
            JsonNode decision = null;
            if (decisions != null && decisions.isArray()) {
                for (JsonNode d : decisions) {
                    if (id.equals(d.path("id").asText())) {
                        decision = d;
                        break;
                    }
                }
            }
            boolean fix = decision != null && decision.path("agree").asBoolean(false);
            String note = decision == null ? "" : decision.path("comment").asText("");
            sb.append("| ").append(id)
                    .append(" | ").append(f.path("title").asText("").replace("|", "/"))
                    .append(" | ").append(f.path("severity").asText(""))
                    .append(" | ").append(fix ? "修改" : "不修改")
                    .append(" | ").append(note.replace("|", "/").replace("\n", " "))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private void markUnitSucceeded(PipeUnitInstance unit, PipeNodeInstance node) {
        // 开发编码 / 问题修复完成后默认 commit，供后续代码评审 master...HEAD 可见
        maybeAutoCommitAfterCoding(unit);
        unit.setStatus("SUCCEEDED");
        unit.setFinishedAt(LocalDateTime.now());
        unit.setErrorMessage(null);
        unitInstanceMapper.updateById(unit);
        if (node != null && CODING_REPO_NODES.contains(node.getNodeCode())) {
            sandboxPipelineSupport.releaseForCoding(unit.getSessionId(), null);
        }
        if (node == null) {
            return;
        }
        List<PipeUnitInstance> siblings = unitInstanceMapper.selectList(new LambdaQueryWrapper<PipeUnitInstance>()
                .eq(PipeUnitInstance::getNodeInstanceId, node.getId()));
        boolean allDone = siblings.stream().allMatch(u ->
                "SUCCEEDED".equals(u.getStatus()) || "SKIPPED".equals(u.getStatus()));
        if (allDone) {
            node.setStatus("SUCCEEDED");
            node.setFinishedAt(LocalDateTime.now());
            nodeInstanceMapper.updateById(node);
        }
    }

    /** 问题修复 / 开发编码成功时，将业务仓 worktree 未提交改动入库。 */
    private void maybeAutoCommitAfterCoding(PipeUnitInstance unit) {
        if (unit == null || !StringUtils.hasText(unit.getTaskTypeCode())) {
            return;
        }
        String code = unit.getTaskTypeCode().trim().toUpperCase(Locale.ROOT);
        if (!"GEN_ISSUE_FIX".equals(code) && !"GEN_CODING".equals(code)) {
            return;
        }
        try {
            PipeRepoWorktree biz = bizWorktree(unit.getInstanceId());
            Path workTree = Path.of(biz.getWorktreePath());
            String ref = StringUtils.hasText(biz.getBranchName()) ? biz.getBranchName() : code;
            String msg = "GEN_ISSUE_FIX".equals(code)
                    ? "fix(" + ref + "): pipeline auto-commit after issue fix"
                    : "feat(" + ref + "): pipeline auto-commit after coding";
            boolean committed = gitWorktreeService.commitAllIfDirty(workTree, msg);
            log.info("auto-commit after {} unit={} committed={} path={}",
                    code, unit.getId(), committed, workTree);
        } catch (Exception e) {
            log.error("auto-commit after {} failed unit={}: {}", code, unit.getId(), e.getMessage());
            throw new BizException("编码完成后自动 commit 失败：" + e.getMessage());
        }
    }

    private void failNode(PipeNodeInstance node, String msg) {
        if (node == null) {
            return;
        }
        node.setStatus("FAILED");
        node.setFinishedAt(LocalDateTime.now());
        nodeInstanceMapper.updateById(node);
        PipeInstance inst = instanceMapper.selectById(node.getInstanceId());
        if (inst != null) {
            inst.setStatus(PipeInstanceStatus.FAILED.name());
            inst.setWaitingReason(msg);
            instanceMapper.updateById(inst);
        }
    }

    private void enqueueAdvance(Long instanceId) {
        PipeJob job = newJob(instanceId, null, PipeJobTypes.ADVANCE);
        jobMapper.insert(job);
    }

    private void runCodingAnalyzeJob(PipeJob job, PipeInstance inst) throws Exception {
        PipeUnitInstance unit = unitInstanceMapper.selectById(job.getUnitInstanceId());
        if (unit == null) {
            throw new BizException("单元不存在");
        }
        unit.setStatus("RUNNING");
        unitInstanceMapper.updateById(unit);
        JsonNode plan = readCodingPlanJson(unit);
        if (plan == null || !plan.path("tasks").isArray() || plan.path("tasks").isEmpty()) {
            unit.setStatus("FAILED");
            unit.setErrorMessage("CODING_ANALYZE 缺少 coding_plan.json 或 tasks 为空");
            unit.setFinishedAt(LocalDateTime.now());
            unitInstanceMapper.updateById(unit);
            throw new BizException(unit.getErrorMessage());
        }
        ObjectNode schedule = CodingScheduleAnalyzer.analyze(plan, objectMapper);
        ObjectNode root = plan.isObject() ? (ObjectNode) plan : objectMapper.createObjectNode();
        if (!plan.isObject()) {
            root.set("tasks", plan.path("tasks"));
        }
        root.set("schedule", schedule);
        persistNamedOutput(unit, "coding_plan.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), false);
        String first = schedule.path("order").isArray() && !schedule.path("order").isEmpty()
                ? schedule.path("order").get(0).asText("")
                : "";
        if (!StringUtils.hasText(first)) {
            unit.setStatus("FAILED");
            unit.setErrorMessage("调度结果无任务");
            unitInstanceMapper.updateById(unit);
            throw new BizException(unit.getErrorMessage());
        }
        clearUnitSessionForNextCodingStage(unit);
        PipeJob next = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODING_TASK);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", first);
        payload.put("taskIndex", 0);
        next.setPayloadJson(payload.toString());
        jobMapper.insert(next);
        log.info("coding analyze done instance={} firstTask={}", inst.getId(), first);
    }

    private void handleCodingStageProgress(
            PipeInstance inst,
            PipeUnitInstance unit,
            PipeNodeInstance node,
            String reply,
            PipeJob job) throws Exception {
        String jobType = job.getJobType();
        if (PipeJobTypes.CODING_PLAN.equals(jobType)) {
            ensureCodingPlanSaved(unit, reply);
            clearUnitSessionForNextCodingStage(unit);
            PipeJob analyze = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODING_ANALYZE);
            jobMapper.insert(analyze);
            unit.setStatus("RUNNING");
            unitInstanceMapper.updateById(unit);
            return;
        }
        if (PipeJobTypes.CODING_TASK.equals(jobType)) {
            String taskId = codingTaskId(job);
            String resultName = "task_result-" + (StringUtils.hasText(taskId) ? taskId : "unknown") + ".json";
            if (StringUtils.hasText(reply) && reply.trim().startsWith("{")) {
                persistNamedOutput(unit, resultName, reply.trim(), false);
            } else if (readOutputText(unit, resultName) == null) {
                ObjectNode stub = objectMapper.createObjectNode();
                stub.put("taskId", taskId);
                stub.put("status", "SUCCEEDED");
                stub.put("summary", reply == null ? "" : truncate(reply, 2000));
                persistNamedOutput(unit, resultName, stub.toString(), false);
            }
            // 必须先算下一卡（读 coding_plan 依赖当前 session），再清会话
            String nextId = nextCodingTaskId(unit, taskId);
            clearUnitSessionForNextCodingStage(unit);
            if (StringUtils.hasText(nextId)) {
                PipeJob next = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODING_TASK);
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("taskId", nextId);
                next.setPayloadJson(payload.toString());
                jobMapper.insert(next);
                log.info("coding task done unit={} finished={} next={}", unit.getId(), taskId, nextId);
            } else {
                // 进入汇总前再验一遍：order 中任一缺 task_result 则回队补做，禁止空汇总
                List<String> missing = missingCodingTaskResults(unit);
                if (!missing.isEmpty()) {
                    String resumeId = missing.get(0);
                    PipeJob resume = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODING_TASK);
                    ObjectNode payload = objectMapper.createObjectNode();
                    payload.put("taskId", resumeId);
                    payload.put("resumeMissing", true);
                    resume.setPayloadJson(payload.toString());
                    jobMapper.insert(resume);
                    log.warn("coding tasks not complete unit={} last={} missing={} → resume {}",
                            unit.getId(), taskId, missing, resumeId);
                } else {
                    PipeJob integ = newJob(inst.getId(), unit.getId(), PipeJobTypes.CODING_INTEGRATE);
                    jobMapper.insert(integ);
                    log.info("coding tasks exhausted unit={} last={} → INTEGRATE", unit.getId(), taskId);
                }
            }
            unit.setStatus("RUNNING");
            unitInstanceMapper.updateById(unit);
            return;
        }
        throw new BizException("未知编码中间阶段: " + jobType);
    }

    private void ensureCodingPlanSaved(PipeUnitInstance unit, String reply) throws Exception {
        if (readOutputText(unit, "coding_plan.json") != null) {
            return;
        }
        String json = extractJsonObject(reply);
        if (json == null) {
            unit.setStatus("FAILED");
            unit.setErrorMessage("未写出 coding_plan.json，请 write_output 该文件名");
            unit.setFinishedAt(LocalDateTime.now());
            unitInstanceMapper.updateById(unit);
            throw new BizException(unit.getErrorMessage());
        }
        persistNamedOutput(unit, "coding_plan.json", json, false);
    }

    private JsonNode readCodingPlanJson(PipeUnitInstance unit) {
        String text = readOutputText(unit, "coding_plan.json");
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("parse coding_plan.json failed: {}", e.getMessage());
            return null;
        }
    }

    private String readOutputText(PipeUnitInstance unit, String fileName) {
        try {
            WbChatSession session = resolveUnitOutputSession(unit);
            if (session == null || session.getOutputDirNodeId() == null) {
                return null;
            }
            SysWorkspaceNode f = workspaceFsService.findChildFile(
                    session.getWorkspaceId(), session.getOutputDirNodeId(), fileName);
            if (f == null) {
                return null;
            }
            var vo = workspaceFsService.readFile(session.getWorkspaceId(), f.getId());
            return vo == null ? null : vo.getContent();
        } catch (Exception e) {
            log.warn("read output {} failed: {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * 编码阶段会清 sessionId；读产出时回退到本单元最近一次带 outputDir 的会话，
     * 避免算下一卡 / 验 task_result 时读不到 coding_plan.json。
     */
    private WbChatSession resolveUnitOutputSession(PipeUnitInstance unit) {
        if (unit == null) {
            return null;
        }
        if (unit.getSessionId() != null) {
            WbChatSession cur = sessionMapper.selectById(unit.getSessionId());
            if (cur != null && cur.getOutputDirNodeId() != null) {
                return cur;
            }
        }
        return sessionMapper.selectOne(new LambdaQueryWrapper<WbChatSession>()
                .eq(WbChatSession::getPipeUnitInstanceId, unit.getId())
                .isNotNull(WbChatSession::getOutputDirNodeId)
                .orderByDesc(WbChatSession::getId)
                .last("LIMIT 1"));
    }

    private JsonNode codingTaskOrder(PipeUnitInstance unit) {
        JsonNode plan = readCodingPlanJson(unit);
        if (plan == null) {
            return null;
        }
        JsonNode order = plan.path("schedule").path("order");
        if (!order.isArray() || order.isEmpty()) {
            order = plan.path("executionOrder");
        }
        return order != null && order.isArray() && !order.isEmpty() ? order : null;
    }

    /** schedule.order / executionOrder 中尚未写出 task_result-{id}.json 的卡。 */
    private List<String> missingCodingTaskResults(PipeUnitInstance unit) {
        List<String> missing = new ArrayList<>();
        JsonNode order = codingTaskOrder(unit);
        if (order == null) {
            return missing;
        }
        for (JsonNode idNode : order) {
            String id = idNode == null ? "" : idNode.asText("").trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            if (readOutputText(unit, "task_result-" + id + ".json") == null) {
                missing.add(id);
            }
        }
        return missing;
    }

    private static boolean isGenCoding(String taskTypeCode) {
        return taskTypeCode != null && "GEN_CODING".equalsIgnoreCase(taskTypeCode.trim());
    }

    private String nextCodingTaskId(PipeUnitInstance unit, String currentId) {
        JsonNode order = codingTaskOrder(unit);
        if (order == null) {
            return null;
        }
        if (!StringUtils.hasText(currentId)) {
            // 无当前卡时：返回第一张缺 result 的，便于恢复
            List<String> missing = missingCodingTaskResults(unit);
            return missing.isEmpty() ? null : missing.get(0);
        }
        for (int i = 0; i < order.size(); i++) {
            if (Objects.equals(currentId, order.get(i).asText())) {
                if (i + 1 < order.size()) {
                    return order.get(i + 1).asText();
                }
                return null;
            }
        }
        // currentId 不在 order：不要误判「耗尽」进汇总，改为第一张缺卡
        List<String> missing = missingCodingTaskResults(unit);
        return missing.isEmpty() ? null : missing.get(0);
    }

    private String codingTaskId(PipeJob job) {
        if (job == null || !StringUtils.hasText(job.getPayloadJson())) {
            return "";
        }
        try {
            return objectMapper.readTree(job.getPayloadJson()).path("taskId").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private void appendCodingTaskKickoff(StringBuilder sb, PipeUnitInstance unit, PipeJob job) {
        String taskId = codingTaskId(job);
        sb.append("当前 taskId=").append(taskId).append('\n');
        JsonNode plan = readCodingPlanJson(unit);
        if (plan == null) {
            return;
        }
        JsonNode tasks = plan.path("tasks");
        if (!tasks.isArray()) {
            return;
        }
        for (JsonNode t : tasks) {
            if (Objects.equals(taskId, t.path("id").asText())) {
                sb.append("任务卡 JSON：\n").append(t).append('\n');
                return;
            }
        }
    }

    private void appendCodingPlanDigest(StringBuilder sb, PipeUnitInstance unit) {
        JsonNode plan = readCodingPlanJson(unit);
        if (plan == null) {
            return;
        }
        sb.append("coding_plan.schedule=").append(plan.path("schedule")).append('\n');
        JsonNode order = plan.path("schedule").path("order");
        if (order.isArray()) {
            for (JsonNode idNode : order) {
                String id = idNode.asText();
                String body = readOutputText(unit, "task_result-" + id + ".json");
                if (StringUtils.hasText(body)) {
                    sb.append("\n--- task_result-").append(id).append(".json ---\n")
                            .append(truncate(body, 3000)).append('\n');
                }
            }
        }
    }

    private String extractJsonObject(String reply) {
        if (!StringUtils.hasText(reply)) {
            return null;
        }
        String t = reply.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String cand = t.substring(start, end + 1);
        try {
            objectMapper.readTree(cand);
            return cand;
        } catch (Exception e) {
            return null;
        }
    }

    private PipeJob newJob(Long instanceId, Long unitId, String type) {
        PipeJob job = new PipeJob();
        job.setInstanceId(instanceId);
        job.setUnitInstanceId(unitId);
        job.setJobType(type);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setNextRunAt(LocalDateTime.now());
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    private void markJob(PipeJob job, String status, String err) {
        job.setStatus(status);
        job.setLastError(err);
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private boolean option(PipeInstance inst, String key) {
        try {
            if (!StringUtils.hasText(inst.getOptionsJson())) {
                return false;
            }
            return objectMapper.readTree(inst.getOptionsJson()).path(key).asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void markOptional(Long instanceId, String nodeCode, boolean enabled) {
        PipeNodeInstance n = findNode(instanceId, nodeCode);
        if (n == null) {
            return;
        }
        n.setEnabledFlag(enabled);
        if (!enabled) {
            n.setStatus("SKIPPED");
            n.setFinishedAt(LocalDateTime.now());
        }
        nodeInstanceMapper.updateById(n);
    }

    private PipeNodeInstance findNode(Long instanceId, String code) {
        return nodeInstanceMapper.selectOne(new LambdaQueryWrapper<PipeNodeInstance>()
                .eq(PipeNodeInstance::getInstanceId, instanceId)
                .eq(PipeNodeInstance::getNodeCode, code)
                .last("LIMIT 1"));
    }

    private boolean nodeSucceeded(Long instanceId, String code) {
        PipeNodeInstance n = findNode(instanceId, code);
        return n != null && "SUCCEEDED".equals(n.getStatus());
    }

    private boolean nodeSkipped(Long instanceId, String code) {
        PipeNodeInstance n = findNode(instanceId, code);
        return n != null && ("SKIPPED".equals(n.getStatus()) || Boolean.FALSE.equals(n.getEnabledFlag()));
    }

    private boolean nodePendingLike(Long instanceId, String code) {
        PipeNodeInstance n = findNode(instanceId, code);
        return n != null && List.of("PENDING", "WAITING_TRIGGER").contains(n.getStatus());
    }

    private boolean nodeRunningLike(Long instanceId, String code) {
        PipeNodeInstance n = findNode(instanceId, code);
        return n != null && List.of("RUNNING", "WAITING_TRIGGER", "WAITING_HUMAN").contains(n.getStatus());
    }

    private void setNodeStatus(Long instanceId, String code, String status) {
        PipeNodeInstance n = findNode(instanceId, code);
        if (n == null) {
            return;
        }
        n.setStatus(status);
        if ("SUCCEEDED".equals(status)) {
            n.setFinishedAt(LocalDateTime.now());
        }
        if (n.getStartedAt() == null) {
            n.setStartedAt(LocalDateTime.now());
        }
        nodeInstanceMapper.updateById(n);
    }

    private PipeUnitInstance requireUnit(Long unitId, Long tenantId) {
        PipeUnitInstance unit = unitInstanceMapper.selectById(unitId);
        if (unit == null) {
            throw new BizException("单元不存在");
        }
        requireInstance(unit.getInstanceId(), tenantId);
        return unit;
    }

    private PipeInstance requireInstance(Long id, Long tenantId) {
        PipeInstance inst = instanceMapper.selectById(id);
        if (inst == null || !Objects.equals(inst.getTenantId(), tenantId)) {
            throw new BizException("流水线实例不存在");
        }
        return inst;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
