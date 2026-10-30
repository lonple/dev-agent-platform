package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.pipeline.dto.PipelineAssigneeVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineInstanceVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStartRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStepGateRequest;
import com.lonple.dap.server.modules.pipeline.dto.PipelineStepVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineUsageRequest;
import com.lonple.dap.server.modules.pipeline.entity.PipeDefinition;
import com.lonple.dap.server.modules.pipeline.entity.PipeInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeStepAssignee;
import com.lonple.dap.server.modules.pipeline.entity.PipeStepDef;
import com.lonple.dap.server.modules.pipeline.entity.PipeStepInstance;
import com.lonple.dap.server.modules.pipeline.entity.PipeTransition;
import com.lonple.dap.server.modules.pipeline.mapper.PipeDefinitionMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeStepAssigneeMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeStepDefMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeStepInstanceMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeTransitionMapper;
import com.lonple.dap.server.modules.product.ProductService;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.chat.mapper.ChatSessionMapper;
import com.lonple.dap.server.modules.wiki.WikiFsService;
import com.lonple.dap.server.modules.wiki.entity.SysWikiNode;
import com.lonple.dap.server.modules.pipeline.dto.PipelineReviewDocVO;
import com.lonple.dap.server.modules.pipeline.dto.PipelineReviewItemVO;
import com.lonple.dap.server.modules.pipeline.entity.PipeArtifact;
import com.lonple.dap.server.modules.pipeline.entity.PipeReview;
import com.lonple.dap.server.modules.pipeline.entity.PipeReviewItem;
import com.lonple.dap.server.modules.pipeline.mapper.PipeReviewItemMapper;
import com.lonple.dap.server.modules.pipeline.mapper.PipeReviewMapper;
import com.lonple.dap.server.modules.product.entity.SysProductCodeRepo;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final PipeDefinitionMapper definitionMapper;
    private final PipeStepDefMapper stepDefMapper;
    private final PipeTransitionMapper transitionMapper;
    private final PipeInstanceMapper instanceMapper;
    private final PipeStepInstanceMapper stepInstanceMapper;
    private final PipeStepAssigneeMapper assigneeMapper;
    private final RequirementMapper requirementMapper;
    private final ProductService productService;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final WikiFsService wikiFsService;
    private final PipeArtifactService artifactService;
    private final PipeReviewItemMapper reviewItemMapper;
    private final PipeReviewMapper reviewMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ObjectProvider<PipelineAiRunner> aiRunnerProvider;

    public PipelineInstanceVO getByRequirement(Long requirementId) {
        requireRequirementInTenant(requirementId);
        PipeInstance inst = findActiveOrLatest(requirementId);
        if (inst == null) {
            return null;
        }
        return toVo(inst);
    }

    @Transactional
    public PipelineInstanceVO start(Long requirementId, PipelineStartRequest req) {
        ReqRequirement requirement = requireRequirementInTenant(requirementId);
        PipeInstance existing = instanceMapper.selectOne(new LambdaQueryWrapper<PipeInstance>()
                .eq(PipeInstance::getRequirementId, requirementId)
                .in(PipeInstance::getStatus, List.of("RUNNING", "WAITING_HUMAN")));
        if (existing != null) {
            throw new BizException(409, "需求已有进行中的流水线实例");
        }

        PipeDefinition def = requireDefaultDefinition();
        List<PipeStepDef> defs = listStepDefs(def.getId());
        if (defs.isEmpty()) {
            throw new BizException("流水线定义无步骤");
        }

        String fromCode = req != null && StringUtils.hasText(req.getFromStepCode())
                ? req.getFromStepCode().trim()
                : defs.getFirst().getCode();
        PipeStepDef startDef = defs.stream()
                .filter(s -> s.getCode().equals(fromCode))
                .findFirst()
                .orElseThrow(() -> new BizException("起始步骤不存在：" + fromCode));

        LocalDateTime now = LocalDateTime.now();
        PipeInstance inst = new PipeInstance();
        inst.setRequirementId(requirement.getId());
        inst.setDefinitionId(def.getId());
        inst.setStatus("RUNNING");
        inst.setCurrentStepCodes("[]");
        inst.setTotalDurationMs(0L);
        inst.setTotalPromptTokens(0L);
        inst.setTotalCompletionTokens(0L);
        inst.setTotalTokens(0L);
        inst.setStartedAt(now);
        inst.setCreatedAt(now);
        inst.setUpdatedAt(now);
        instanceMapper.insert(inst);

        SysProduct product = productService.requireOwned(requirement.getProductId());
        productService.assertPipelineReady(product);
        seedRawReqArtifact(requirement, product);
        enterStep(inst, startDef, resolveHandler(requirement, startDef));
        return toVo(instanceMapper.selectById(inst.getId()));
    }

    /** 人工触发：准备任务会话 + kickoff 文案，前端再流式调用 Agent */
    @Transactional
    public com.lonple.dap.server.modules.pipeline.dto.PipelineAiPrepareVO prepareAi(
            Long requirementId, Long stepInstanceId) {
        PipeInstance inst = requireRunningInstance(requirementId);
        PipeStepInstance step = requireStep(inst.getId(), stepInstanceId);
        var prepared = aiRunnerProvider.getObject().prepare(step.getId());
        prepared.setInstance(toVo(instanceMapper.selectById(inst.getId())));
        return prepared;
    }

    /** 会话 done 后：落 Wiki 产物并推进（stream 未完成则拒绝） */
    @Transactional
    public PipelineInstanceVO finalizeAi(Long requirementId, Long stepInstanceId) {
        PipeInstance inst = requireRunningInstance(requirementId);
        requireStep(inst.getId(), stepInstanceId);
        var payload = aiRunnerProvider.getObject().finalizeFromSession(stepInstanceId);
        finishAiSuccess(stepInstanceId, payload.artifactId(), payload.reviewId(), 0, 0, 0);
        return toVo(instanceMapper.selectById(inst.getId()));
    }

    /** 失败 / 待触发：回到 WAITING_TRIGGER */
    @Transactional
    public PipelineInstanceVO retryAiStep(Long requirementId, Long stepInstanceId) {
        PipeInstance inst = requireRunningInstance(requirementId);
        PipeStepInstance step = requireStep(inst.getId(), stepInstanceId);
        if (!List.of("AI_GENERATE", "AI_REVIEW", "AI_REVISE").contains(step.getExecutor())) {
            throw new BizException("仅 AI 步骤可重试");
        }
        if (!List.of("FAILED", "RUNNING", "WAITING_TRIGGER").contains(step.getStatus())) {
            throw new BizException("当前状态不可重试：" + step.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        step.setStatus("WAITING_TRIGGER");
        step.setErrorMessage(null);
        step.setSessionId(null);
        step.setFinishedAt(null);
        step.setDurationMs(0L);
        step.setUpdatedAt(now);
        stepInstanceMapper.updateById(step);
        inst.setStatus("WAITING_HUMAN");
        inst.setWaitingReason("TRIGGER_AI");
        inst.setCurrentStepCodes(writeJson(List.of(step.getStepCode())));
        inst.setUpdatedAt(now);
        instanceMapper.updateById(inst);
        return toVo(instanceMapper.selectById(inst.getId()));
    }

    @Transactional
    public void finishAiSuccess(Long stepInstanceId, Long artifactId, Long reviewId,
                                long promptTokens, long completionTokens, long totalTokens) {
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null || !"RUNNING".equals(step.getStatus())) {
            return;
        }
        PipeInstance inst = instanceMapper.selectById(step.getInstanceId());
        if (inst == null || !List.of("RUNNING", "WAITING_HUMAN").contains(inst.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        PipelineUsageRequest usage = new PipelineUsageRequest();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(totalTokens);
        addUsage(step, usage);
        finishStepTiming(step, now);
        step.setStatus("SUCCEEDED");
        step.setOutputArtifactId(artifactId);
        step.setReviewId(reviewId);
        step.setErrorMessage(null);
        step.setUpdatedAt(now);
        stepInstanceMapper.updateById(step);
        advance(inst, step.getStepCode(), "SUCCESS");
        refreshInstanceTotals(inst.getId());
    }

    @Transactional
    public void markAiFailed(Long stepInstanceId, String errorMessage) {
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null) {
            return;
        }
        if (!"RUNNING".equals(step.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        finishStepTiming(step, now);
        step.setStatus("FAILED");
        step.setErrorMessage(errorMessage);
        step.setUpdatedAt(now);
        stepInstanceMapper.updateById(step);
        PipeInstance inst = instanceMapper.selectById(step.getInstanceId());
        if (inst != null) {
            inst.setWaitingReason("AI_FAILED");
            inst.setUpdatedAt(now);
            // 保持 RUNNING，允许重试；不整条 CANCELLED
            if (!"CANCELLED".equals(inst.getStatus()) && !"DONE".equals(inst.getStatus())) {
                inst.setStatus("RUNNING");
            }
            instanceMapper.updateById(inst);
            refreshInstanceTotals(inst.getId());
        }
    }

    @Transactional
    public PipelineInstanceVO cancel(Long requirementId) {
        PipeInstance inst = requireRunningInstance(requirementId);
        LocalDateTime now = LocalDateTime.now();
        List<PipeStepInstance> open = stepInstanceMapper.selectList(new LambdaQueryWrapper<PipeStepInstance>()
                .eq(PipeStepInstance::getInstanceId, inst.getId())
                .in(PipeStepInstance::getStatus, List.of("RUNNING", "WAITING_HUMAN", "PENDING")));
        for (PipeStepInstance step : open) {
            finishStepTiming(step, now);
            step.setStatus("CANCELLED");
            step.setUpdatedAt(now);
            stepInstanceMapper.updateById(step);
        }
        inst.setStatus("CANCELLED");
        inst.setFinishedAt(now);
        inst.setCurrentStepCodes("[]");
        inst.setWaitingReason(null);
        inst.setUpdatedAt(now);
        refreshInstanceTotals(inst.getId());
        instanceMapper.updateById(instanceMapper.selectById(inst.getId()));
        return toVo(instanceMapper.selectById(inst.getId()));
    }

    @Transactional
    public PipelineInstanceVO approveStep(Long requirementId, Long stepInstanceId, PipelineStepGateRequest req) {
        return gateStep(requirementId, stepInstanceId, true, req);
    }

    @Transactional
    public PipelineInstanceVO rejectStep(Long requirementId, Long stepInstanceId, PipelineStepGateRequest req) {
        return gateStep(requirementId, stepInstanceId, false, req);
    }

    /**
     * AI 步骤完成（Agent 跑完或调试跳过）。写入耗时；可选累加 token。
     */
    @Transactional
    public PipelineInstanceVO completeAiStep(Long requirementId, Long stepInstanceId, PipelineUsageRequest usage) {
        PipeInstance inst = requireRunningInstance(requirementId);
        PipeStepInstance step = requireStep(inst.getId(), stepInstanceId);
        if (!List.of("RUNNING", "WAITING_HUMAN", "WAITING_TRIGGER", "FAILED").contains(step.getStatus())) {
            throw new BizException("步骤当前不可完成：" + step.getStatus());
        }
        if (!List.of("AI_GENERATE", "AI_REVIEW", "AI_REVISE").contains(step.getExecutor())) {
            throw new BizException("仅 AI 步骤可调用 complete");
        }
        LocalDateTime now = LocalDateTime.now();
        addUsage(step, usage);
        finishStepTiming(step, now);
        step.setStatus("SUCCEEDED");
        step.setUpdatedAt(now);
        stepInstanceMapper.updateById(step);
        advance(inst, step.getStepCode(), "SUCCESS");
        refreshInstanceTotals(inst.getId());
        return toVo(instanceMapper.selectById(inst.getId()));
    }

    /** 会话结束后累加 token（不结束步骤） */
    @Transactional
    public void addUsageBySession(Long sessionId, long prompt, long completion, long total) {
        if (sessionId == null) {
            return;
        }
        PipeStepInstance step = stepInstanceMapper.selectOne(new LambdaQueryWrapper<PipeStepInstance>()
                .eq(PipeStepInstance::getSessionId, sessionId)
                .in(PipeStepInstance::getStatus, List.of("RUNNING", "WAITING_HUMAN"))
                .orderByDesc(PipeStepInstance::getId)
                .last("LIMIT 1"));
        if (step != null) {
            addUsageToStep(step, prompt, completion, total);
        }
    }

    @Transactional
    public void addUsageByStepInstance(Long stepInstanceId, long prompt, long completion, long total) {
        if (stepInstanceId == null) {
            return;
        }
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null) {
            return;
        }
        addUsageToStep(step, prompt, completion, total);
    }

    private void addUsageToStep(PipeStepInstance step, long prompt, long completion, long total) {
        PipelineUsageRequest u = new PipelineUsageRequest();
        u.setPromptTokens(prompt);
        u.setCompletionTokens(completion);
        u.setTotalTokens(total > 0 ? total : prompt + completion);
        addUsage(step, u);
        step.setUpdatedAt(LocalDateTime.now());
        stepInstanceMapper.updateById(step);
        refreshInstanceTotals(step.getInstanceId());
    }

    @Transactional
    public void bindSession(Long stepInstanceId, Long sessionId) {
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null) {
            return;
        }
        step.setSessionId(sessionId);
        step.setUpdatedAt(LocalDateTime.now());
        stepInstanceMapper.updateById(step);
    }

    private PipelineInstanceVO gateStep(Long requirementId, Long stepInstanceId, boolean approve,
                                       PipelineStepGateRequest req) {
        PipeInstance inst = requireRunningInstance(requirementId);
        PipeStepInstance step = requireStep(inst.getId(), stepInstanceId);
        if (!"WAITING_HUMAN".equals(step.getStatus()) && !"HUMAN_APPROVE".equals(step.getExecutor())) {
            throw new BizException("步骤不在人闸等待中");
        }
        Long userId = TenantSupport.requireUserId();
        PipeStepAssignee assignee = assigneeMapper.selectOne(new LambdaQueryWrapper<PipeStepAssignee>()
                .eq(PipeStepAssignee::getStepInstanceId, step.getId())
                .eq(PipeStepAssignee::getUserId, userId));
        if (assignee == null && step.getHandlerUserId() != null
                && !Objects.equals(step.getHandlerUserId(), userId)) {
            throw new BizException("你不是本步骤处理人");
        }
        LocalDateTime now = LocalDateTime.now();
        if (assignee != null) {
            assignee.setStatus(approve ? "APPROVED" : "REJECTED");
            assignee.setActedAt(now);
            assignee.setCommentText(req != null ? req.getComment() : null);
            assigneeMapper.updateById(assignee);
        }
        if (!approve) {
            finishStepTiming(step, now);
            step.setStatus("FAILED");
            step.setErrorMessage(req != null && StringUtils.hasText(req.getComment())
                    ? req.getComment().trim() : "已驳回");
            step.setUpdatedAt(now);
            stepInstanceMapper.updateById(step);
            inst.setStatus("CANCELLED");
            inst.setFinishedAt(now);
            inst.setWaitingReason(null);
            inst.setCurrentStepCodes("[]");
            inst.setUpdatedAt(now);
            refreshInstanceTotals(inst.getId());
            PipeInstance latest = instanceMapper.selectById(inst.getId());
            latest.setStatus("CANCELLED");
            latest.setFinishedAt(now);
            latest.setWaitingReason(null);
            latest.setCurrentStepCodes("[]");
            latest.setUpdatedAt(now);
            instanceMapper.updateById(latest);
            return toVo(latest);
        }
        finishStepTiming(step, now);
        step.setStatus("SUCCEEDED");
        step.setUpdatedAt(now);
        stepInstanceMapper.updateById(step);
        advance(inst, step.getStepCode(), "APPROVE");
        refreshInstanceTotals(inst.getId());
        return toVo(instanceMapper.selectById(inst.getId()));
    }

    private void advance(PipeInstance inst, String fromCode, String event) {
        List<PipeTransition> edges = transitionMapper.selectList(new LambdaQueryWrapper<PipeTransition>()
                .eq(PipeTransition::getDefinitionId, inst.getDefinitionId())
                .eq(PipeTransition::getFromStepCode, fromCode)
                .eq(PipeTransition::getEvent, event)
                .orderByAsc(PipeTransition::getPriority));
        if (edges.isEmpty()) {
            if ("PIPELINE_DONE".equals(fromCode) || "SYSTEM".equals(fromCode)) {
                completeInstance(inst);
            }
            return;
        }
        PipeTransition edge = edges.getFirst();
        if ("PIPELINE_DONE".equals(edge.getToStepCode())) {
            PipeStepDef doneDef = stepDefMapper.selectOne(new LambdaQueryWrapper<PipeStepDef>()
                    .eq(PipeStepDef::getDefinitionId, inst.getDefinitionId())
                    .eq(PipeStepDef::getCode, "PIPELINE_DONE"));
            if (doneDef != null) {
                PipeStepInstance done = createStepRow(inst, doneDef, null);
                LocalDateTime now = LocalDateTime.now();
                done.setStatus("SUCCEEDED");
                done.setStartedAt(now);
                done.setFinishedAt(now);
                done.setDurationMs(0L);
                done.setUpdatedAt(now);
                stepInstanceMapper.updateById(done);
            }
            completeInstance(inst);
            return;
        }
        PipeStepDef next = stepDefMapper.selectOne(new LambdaQueryWrapper<PipeStepDef>()
                .eq(PipeStepDef::getDefinitionId, inst.getDefinitionId())
                .eq(PipeStepDef::getCode, edge.getToStepCode()));
        if (next == null) {
            throw new BizException("下一跳步骤定义缺失：" + edge.getToStepCode());
        }
        ReqRequirement requirement = requirementMapper.selectById(inst.getRequirementId());
        enterStep(inst, next, resolveHandler(requirement, next));
    }

    private void enterStep(PipeInstance inst, PipeStepDef def, Long handlerUserId) {
        PipeStepInstance step = createStepRow(inst, def, handlerUserId);
        LocalDateTime now = LocalDateTime.now();
        step.setStartedAt(now);
        step.setUpdatedAt(now);

        if ("HUMAN_APPROVE".equals(def.getExecutor())) {
            step.setStatus("WAITING_HUMAN");
            stepInstanceMapper.updateById(step);
            PipeStepAssignee a = new PipeStepAssignee();
            a.setStepInstanceId(step.getId());
            a.setUserId(handlerUserId != null ? handlerUserId : TenantSupport.requireUserId());
            a.setStatus("PENDING");
            assigneeMapper.insert(a);
            inst.setStatus("WAITING_HUMAN");
            inst.setWaitingReason("APPROVE");
            inst.setCurrentStepCodes(writeJson(List.of(def.getCode())));
            inst.setUpdatedAt(now);
            instanceMapper.updateById(inst);
            return;
        }
        if ("SYSTEM".equals(def.getExecutor()) || "PIPELINE_DONE".equals(def.getCode())) {
            step.setStatus("SUCCEEDED");
            step.setFinishedAt(now);
            step.setDurationMs(0L);
            stepInstanceMapper.updateById(step);
            completeInstance(inst);
            return;
        }
        // AI 步骤：开发阶段须人工点「执行」才 kickoff
        step.setStatus("WAITING_TRIGGER");
        stepInstanceMapper.updateById(step);
        inst.setStatus("WAITING_HUMAN");
        inst.setWaitingReason("TRIGGER_AI");
        inst.setCurrentStepCodes(writeJson(List.of(def.getCode())));
        inst.setUpdatedAt(now);
        instanceMapper.updateById(inst);
    }

    private void seedRawReqArtifact(ReqRequirement requirement, SysProduct product) {
        try {
            if (artifactService.findCurrent(requirement.getId(), "RAW_REQ") != null) {
                return;
            }
            Long reqDirId = wikiFsService.ensureRequirementDir(product.getWikiNodeId(), requirement);
            SysWikiNode raw = wikiFsService.findChildFile(reqDirId, WikiFsService.DEFAULT_REQ_MARKDOWN_NAME);
            if (raw != null) {
                artifactService.register(requirement.getId(), "RAW_REQ", raw.getId(), null, "原始需求");
            }
        } catch (Exception e) {
            log.warn("seed RAW_REQ failed reqId={}: {}", requirement.getId(), e.getMessage());
        }
    }

    private PipeStepInstance createStepRow(PipeInstance inst, PipeStepDef def, Long handlerUserId) {
        LocalDateTime now = LocalDateTime.now();
        PipeStepInstance step = new PipeStepInstance();
        step.setInstanceId(inst.getId());
        step.setStepCode(def.getCode());
        step.setStepName(def.getName());
        step.setStatus("PENDING");
        step.setRound(1);
        step.setExecutor(def.getExecutor());
        step.setHandlerUserId(handlerUserId);
        step.setDurationMs(0L);
        step.setPromptTokens(0L);
        step.setCompletionTokens(0L);
        step.setTotalTokens(0L);
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        stepInstanceMapper.insert(step);
        return step;
    }

    private void completeInstance(PipeInstance inst) {
        LocalDateTime now = LocalDateTime.now();
        PipeInstance latest = instanceMapper.selectById(inst.getId());
        latest.setStatus("DONE");
        latest.setFinishedAt(now);
        latest.setWaitingReason(null);
        latest.setCurrentStepCodes("[]");
        latest.setUpdatedAt(now);
        instanceMapper.updateById(latest);
        refreshInstanceTotals(latest.getId());
    }

    private void finishStepTiming(PipeStepInstance step, LocalDateTime now) {
        if (step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        step.setFinishedAt(now);
        long ms = Duration.between(step.getStartedAt(), now).toMillis();
        if (ms < 0) {
            ms = 0;
        }
        step.setDurationMs(ms);
    }

    private void addUsage(PipeStepInstance step, PipelineUsageRequest usage) {
        if (usage == null) {
            return;
        }
        long p = nvl(usage.getPromptTokens());
        long c = nvl(usage.getCompletionTokens());
        long t = nvl(usage.getTotalTokens());
        if (t <= 0) {
            t = p + c;
        }
        step.setPromptTokens(nvl(step.getPromptTokens()) + p);
        step.setCompletionTokens(nvl(step.getCompletionTokens()) + c);
        step.setTotalTokens(nvl(step.getTotalTokens()) + t);
    }

    private void refreshInstanceTotals(Long instanceId) {
        List<PipeStepInstance> steps = stepInstanceMapper.selectList(new LambdaQueryWrapper<PipeStepInstance>()
                .eq(PipeStepInstance::getInstanceId, instanceId));
        long dur = 0;
        long pt = 0;
        long ct = 0;
        long tt = 0;
        for (PipeStepInstance s : steps) {
            dur += nvl(s.getDurationMs());
            pt += nvl(s.getPromptTokens());
            ct += nvl(s.getCompletionTokens());
            tt += nvl(s.getTotalTokens());
        }
        PipeInstance inst = instanceMapper.selectById(instanceId);
        inst.setTotalDurationMs(dur);
        inst.setTotalPromptTokens(pt);
        inst.setTotalCompletionTokens(ct);
        inst.setTotalTokens(tt);
        inst.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(inst);
    }

    private Long resolveHandler(ReqRequirement requirement, PipeStepDef def) {
        if (requirement == null) {
            return TenantSupport.requireUserId();
        }
        String role = def.getHumanRole();
        if ("PM".equals(role)) {
            try {
                SysProduct product = productService.requireOwned(requirement.getProductId());
                if (product.getManagerUserId() != null) {
                    return product.getManagerUserId();
                }
            } catch (Exception ignored) {
                // fall through
            }
            if (requirement.getApproverId() != null) {
                return requirement.getApproverId();
            }
        }
        if (requirement.getCurrentHandlerId() != null) {
            return requirement.getCurrentHandlerId();
        }
        return requirement.getCreatedBy() != null ? requirement.getCreatedBy() : TenantSupport.requireUserId();
    }

    private PipeDefinition requireDefaultDefinition() {
        PipeDefinition def = definitionMapper.selectOne(new LambdaQueryWrapper<PipeDefinition>()
                .eq(PipeDefinition::getCode, "RD_AUTO")
                .eq(PipeDefinition::getEnabled, true)
                .isNull(PipeDefinition::getTenantId)
                .orderByDesc(PipeDefinition::getVersion)
                .last("LIMIT 1"));
        if (def == null) {
            throw new BizException("未找到流水线定义 RD_AUTO");
        }
        return def;
    }

    private List<PipeStepDef> listStepDefs(Long definitionId) {
        return stepDefMapper.selectList(new LambdaQueryWrapper<PipeStepDef>()
                .eq(PipeStepDef::getDefinitionId, definitionId)
                .orderByAsc(PipeStepDef::getSortOrder));
    }

    private ReqRequirement requireRequirementInTenant(Long requirementId) {
        Long tenantId = TenantSupport.requireTenantId();
        ReqRequirement entity = requirementMapper.selectById(requirementId);
        if (entity == null || !Objects.equals(entity.getTenantId(), tenantId)) {
            throw new BizException(404, "需求不存在");
        }
        return entity;
    }

    private PipeInstance requireRunningInstance(Long requirementId) {
        requireRequirementInTenant(requirementId);
        PipeInstance inst = instanceMapper.selectOne(new LambdaQueryWrapper<PipeInstance>()
                .eq(PipeInstance::getRequirementId, requirementId)
                .in(PipeInstance::getStatus, List.of("RUNNING", "WAITING_HUMAN")));
        if (inst == null) {
            throw new BizException("没有进行中的流水线");
        }
        return inst;
    }

    private PipeStepInstance requireStep(Long instanceId, Long stepInstanceId) {
        PipeStepInstance step = stepInstanceMapper.selectById(stepInstanceId);
        if (step == null || !Objects.equals(step.getInstanceId(), instanceId)) {
            throw new BizException(404, "步骤不存在");
        }
        return step;
    }

    private PipeInstance findActiveOrLatest(Long requirementId) {
        PipeInstance active = instanceMapper.selectOne(new LambdaQueryWrapper<PipeInstance>()
                .eq(PipeInstance::getRequirementId, requirementId)
                .in(PipeInstance::getStatus, List.of("RUNNING", "WAITING_HUMAN"))
                .last("LIMIT 1"));
        if (active != null) {
            return active;
        }
        return instanceMapper.selectOne(new LambdaQueryWrapper<PipeInstance>()
                .eq(PipeInstance::getRequirementId, requirementId)
                .orderByDesc(PipeInstance::getId)
                .last("LIMIT 1"));
    }

    private PipelineInstanceVO toVo(PipeInstance inst) {
        PipeDefinition def = definitionMapper.selectById(inst.getDefinitionId());
        PipelineInstanceVO vo = new PipelineInstanceVO();
        vo.setInstanceId(inst.getId());
        vo.setRequirementId(inst.getRequirementId());
        vo.setDefinitionCode(def != null ? def.getCode() : null);
        vo.setDefinitionVersion(def != null ? def.getVersion() : null);
        vo.setStatus(inst.getStatus());
        vo.setWaitingReason(inst.getWaitingReason());
        vo.setCurrentStepCodes(readStringList(inst.getCurrentStepCodes()));
        vo.setTotalDurationMs(nvl(inst.getTotalDurationMs()));
        vo.setTotalPromptTokens(nvl(inst.getTotalPromptTokens()));
        vo.setTotalCompletionTokens(nvl(inst.getTotalCompletionTokens()));
        vo.setTotalTokens(nvl(inst.getTotalTokens()));
        vo.setStartedAt(inst.getStartedAt());
        vo.setFinishedAt(inst.getFinishedAt());

        List<PipeStepInstance> steps = stepInstanceMapper.selectList(new LambdaQueryWrapper<PipeStepInstance>()
                .eq(PipeStepInstance::getInstanceId, inst.getId())
                .orderByAsc(PipeStepInstance::getId));
        List<PipelineStepVO> stepVos = new ArrayList<>();
        for (PipeStepInstance s : steps) {
            stepVos.add(toStepVo(s));
        }
        vo.setSteps(stepVos);
        return vo;
    }

    private PipelineStepVO toStepVo(PipeStepInstance s) {
        PipelineStepVO vo = new PipelineStepVO();
        vo.setStepInstanceId(s.getId());
        vo.setStepCode(s.getStepCode());
        vo.setStepName(s.getStepName());
        vo.setStatus(s.getStatus());
        vo.setRound(s.getRound());
        vo.setExecutor(s.getExecutor());
        vo.setHandlerUserId(s.getHandlerUserId());
        vo.setSessionId(s.getSessionId());
        vo.setOutputArtifactId(s.getOutputArtifactId());
        vo.setReviewId(s.getReviewId());
        vo.setErrorMessage(s.getErrorMessage());
        vo.setStartedAt(s.getStartedAt());
        vo.setFinishedAt(s.getFinishedAt());
        vo.setDurationMs(nvl(s.getDurationMs()));
        vo.setPromptTokens(nvl(s.getPromptTokens()));
        vo.setCompletionTokens(nvl(s.getCompletionTokens()));
        vo.setTotalTokens(nvl(s.getTotalTokens()));
        List<PipeStepAssignee> assignees = assigneeMapper.selectList(new LambdaQueryWrapper<PipeStepAssignee>()
                .eq(PipeStepAssignee::getStepInstanceId, s.getId()));
        vo.setAssignees(assignees.stream().map(a -> {
            PipelineAssigneeVO av = new PipelineAssigneeVO();
            av.setUserId(a.getUserId());
            av.setStatus(a.getStatus());
            av.setActedAt(a.getActedAt());
            SysUser u = userMapper.selectById(a.getUserId());
            av.setUsername(u != null ? u.getUsername() : null);
            return av;
        }).collect(Collectors.toList()));
        enrichStepPaths(s, vo);
        enrichReviewItems(s, vo);
        return vo;
    }

    private void enrichStepPaths(PipeStepInstance s, PipelineStepVO vo) {
        try {
            PipeInstance inst = instanceMapper.selectById(s.getInstanceId());
            if (inst == null) {
                return;
            }
            ReqRequirement req = requirementMapper.selectById(inst.getRequirementId());
            if (req == null) {
                return;
            }
            SysProduct product = productService.requireOwned(req.getProductId());
            if (product.getWikiNodeId() != null) {
                SysWikiNode ctx = wikiFsService.requireNode(product.getWikiNodeId());
                String ctxPath = wikiFsService.buildDisplayPath(ctx);
                vo.setContextPaths(List.of(ctxPath));
                vo.setOutputDirPath(ctxPath + "/" + req.getReqNo());
            }
            for (SysProductCodeRepo repo : productService.listCodeRepos(product.getId())) {
                vo.getCodeRepoPaths().add(repo.getLocalPath());
            }
            PipeStepDef def = stepDefMapper.selectOne(new LambdaQueryWrapper<PipeStepDef>()
                    .eq(PipeStepDef::getDefinitionId, inst.getDefinitionId())
                    .eq(PipeStepDef::getCode, s.getStepCode()));
            if (def != null) {
                for (String kind : aiRunnerProvider.getObject().resolveInputKinds(def)) {
                    PipeArtifact art = artifactService.findCurrent(req.getId(), kind);
                    if (art != null && art.getWikiNodeId() != null) {
                        SysWikiNode n = wikiFsService.requireNode(art.getWikiNodeId());
                        vo.getInputPaths().add(kind + ": " + wikiFsService.buildDisplayPath(n));
                    } else {
                        vo.getInputPaths().add(kind + ": （尚未产出）");
                    }
                }
                if (StringUtils.hasText(def.getTemplateWikiPath())) {
                    vo.setOutputTemplatePath(def.getTemplateWikiPath());
                }
            }
            enrichFromSession(s, vo);
        } catch (Exception e) {
            log.debug("enrich step paths skipped: {}", e.getMessage());
        }
    }

    private void enrichFromSession(PipeStepInstance s, PipelineStepVO vo) {
        if (s.getSessionId() == null) {
            return;
        }
        WbChatSession sess = chatSessionMapper.selectById(s.getSessionId());
        if (sess == null) {
            return;
        }
        if (sess.getOutputWikiDirNodeId() != null) {
            try {
                SysWikiNode dir = wikiFsService.requireNode(sess.getOutputWikiDirNodeId());
                vo.setOutputDirPath(wikiFsService.buildDisplayPath(dir));
            } catch (Exception ignored) {
                // keep previous
            }
        }
        if (sess.getOutputTemplateWikiNodeId() != null) {
            try {
                SysWikiNode tpl = wikiFsService.requireNode(sess.getOutputTemplateWikiNodeId());
                vo.setOutputTemplatePath(wikiFsService.buildDisplayPath(tpl));
            } catch (Exception ignored) {
                // keep previous
            }
        }
    }

    private void enrichReviewItems(PipeStepInstance s, PipelineStepVO vo) {
        List<PipeStepInstance> reviewSteps = new ArrayList<>();
        if (s.getReviewId() != null) {
            reviewSteps.add(s);
        } else if ("HUMAN_APPROVE".equals(s.getExecutor())) {
            // 确认步：自本步向前回溯，合并紧邻的连续 AI_REVIEW（多角色共用模板 → 合并有效项）
            List<PipeStepInstance> ordered = stepInstanceMapper.selectList(new LambdaQueryWrapper<PipeStepInstance>()
                    .eq(PipeStepInstance::getInstanceId, s.getInstanceId())
                    .orderByAsc(PipeStepInstance::getId));
            for (int i = ordered.size() - 1; i >= 0; i--) {
                if (Objects.equals(ordered.get(i).getId(), s.getId())) {
                    for (int j = i - 1; j >= 0; j--) {
                        PipeStepInstance prev = ordered.get(j);
                        if ("AI_REVIEW".equals(prev.getExecutor())) {
                            if ("SUCCEEDED".equals(prev.getStatus()) && prev.getReviewId() != null) {
                                reviewSteps.add(0, prev);
                            }
                            continue;
                        }
                        break;
                    }
                    break;
                }
            }
        }
        if (reviewSteps.isEmpty()) {
            return;
        }
        Long primaryReviewId = null;
        for (PipeStepInstance rs : reviewSteps) {
            Long rid = rs.getReviewId();
            if (rid == null) {
                continue;
            }
            if (primaryReviewId == null) {
                primaryReviewId = rid;
                vo.setReviewId(rid);
            }
            PipeReview review = reviewMapper.selectById(rid);
            PipelineReviewDocVO doc = new PipelineReviewDocVO();
            doc.setReviewId(rid);
            doc.setStepInstanceId(rs.getId());
            doc.setStepName(rs.getStepName());
            doc.setRoleHint(rs.getStepCode());
            if (review != null && review.getSummaryWikiNodeId() != null) {
                doc.setWikiNodeId(review.getSummaryWikiNodeId());
                try {
                    SysWikiNode n = wikiFsService.requireNode(review.getSummaryWikiNodeId());
                    doc.setWikiPath(wikiFsService.buildDisplayPath(n));
                } catch (Exception ignored) {
                    // ignore
                }
                if (vo.getReviewWikiNodeId() == null) {
                    vo.setReviewWikiNodeId(review.getSummaryWikiNodeId());
                    vo.setReviewWikiPath(doc.getWikiPath());
                }
            }
            vo.getReviewDocs().add(doc);

            List<PipeReviewItem> items = reviewItemMapper.selectList(new LambdaQueryWrapper<PipeReviewItem>()
                    .eq(PipeReviewItem::getReviewId, rid)
                    .orderByAsc(PipeReviewItem::getOrdinal));
            for (PipeReviewItem it : items) {
                PipelineReviewItemVO iv = new PipelineReviewItemVO();
                iv.setId(it.getId());
                iv.setOrdinal(it.getOrdinal());
                iv.setContent(it.getContent());
                iv.setChecked(it.getChecked());
                iv.setSource(it.getSource());
                vo.getReviewItems().add(iv);
            }
        }
    }

    @Transactional
    public void updateReviewItems(Long requirementId, Long stepInstanceId, List<PipelineReviewItemVO> items) {
        PipeInstance inst = requireRunningInstance(requirementId);
        PipeStepInstance step = requireStep(inst.getId(), stepInstanceId);
        if (!"WAITING_HUMAN".equals(step.getStatus()) || !"HUMAN_APPROVE".equals(step.getExecutor())) {
            throw new BizException("仅确认人闸可改评审项");
        }
        PipelineStepVO tmp = new PipelineStepVO();
        enrichReviewItems(step, tmp);
        if (tmp.getReviewItems().isEmpty() && tmp.getReviewId() == null) {
            throw new BizException("无评审项");
        }
        java.util.Set<Long> allowedReviewIds = tmp.getReviewDocs().stream()
                .map(PipelineReviewDocVO::getReviewId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (allowedReviewIds.isEmpty() && tmp.getReviewId() != null) {
            allowedReviewIds.add(tmp.getReviewId());
        }
        if (items == null) {
            return;
        }
        Long userId = TenantSupport.requireUserId();
        java.util.Set<Long> keepIds = items.stream()
                .map(PipelineReviewItemVO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (PipelineReviewItemVO existing : tmp.getReviewItems()) {
            if (existing.getId() != null && !keepIds.contains(existing.getId())) {
                reviewItemMapper.deleteById(existing.getId());
            }
        }
        int nextOrdinal = tmp.getReviewItems().size();
        for (PipelineReviewItemVO iv : items) {
            if (iv.getId() == null) {
                // 新增：挂到主 review
                Long reviewId = tmp.getReviewId();
                if (reviewId == null) {
                    continue;
                }
                if (!StringUtils.hasText(iv.getContent())) {
                    continue;
                }
                PipeReviewItem row = new PipeReviewItem();
                row.setReviewId(reviewId);
                row.setOrdinal(nextOrdinal++);
                row.setContent(iv.getContent().trim());
                row.setChecked(Boolean.TRUE.equals(iv.getChecked()));
                row.setSource("HUMAN");
                row.setAmendUserId(userId);
                row.setCreatedAt(LocalDateTime.now());
                reviewItemMapper.insert(row);
                continue;
            }
            PipeReviewItem row = reviewItemMapper.selectById(iv.getId());
            if (row == null || !allowedReviewIds.contains(row.getReviewId())) {
                continue;
            }
            if (StringUtils.hasText(iv.getContent())) {
                row.setContent(iv.getContent().trim());
            }
            row.setChecked(Boolean.TRUE.equals(iv.getChecked()));
            row.setAmendUserId(userId);
            reviewItemMapper.updateById(row);
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }
}
