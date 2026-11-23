package com.lonple.dap.server.modules.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.chat.SessionTaskType;
import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.prompt.dto.PromptSaveRequest;
import com.lonple.dap.server.modules.prompt.dto.PromptVO;
import com.lonple.dap.server.modules.prompt.entity.SysPrompt;
import com.lonple.dap.server.modules.prompt.mapper.PromptMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptMapper promptMapper;
    private final PositionService positionService;

    /**
     * 初始化流水线各工作节点、各岗位默认提示词（不覆盖已有岗位+任务类型绑定）。
     * 覆盖：初审、生成、设计侧评审/修订、详设/用例、编码、集成测试等全环节专用正文；
     * 对照文档：doc/需求/流水线节点与Agent配置.md。
     */
    @Transactional
    public Map<String, Object> ensurePreReviewPrompts() {
        Long tenantId = TenantSupport.requireTenantId();
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        // 保证流水线岗位编码齐全（尤其 UI，历史租户常缺）
        positionService.ensureBuiltin("PM", "产品经理");
        positionService.ensureBuiltin("ARCH", "架构师");
        positionService.ensureBuiltin("DEV", "研发");
        positionService.ensureBuiltin("QA", "测试");
        positionService.ensureBuiltin("UI", "UI设计师");

        // AI 初审两岗：默认提示词有更新时覆盖正文（岗位关注点调整）
        ensureOne(tenantId, "PM", SessionTaskType.REVIEW_PRE,
                "REVIEW_PRE_PM", "AI初审·业务（产品）",
                "prompt-defaults/REVIEW_PRE_PM.md", created, skipped, missing, true);
        ensureOne(tenantId, "ARCH", SessionTaskType.REVIEW_PRE_TECH,
                "REVIEW_PRE_TECH", "AI初审·技术（架构）",
                "prompt-defaults/REVIEW_PRE_TECH.md", created, skipped, missing, true);
        ensureOne(tenantId, "PM", SessionTaskType.GEN_PRD,
                "GEN_PRD_PM", "生成需求分析（产品）",
                "prompt-defaults/GEN_PRD_PM.md", created, skipped, missing);
        ensureOne(tenantId, "ARCH", SessionTaskType.GEN_ARCH,
                "GEN_ARCH_ARCH", "生成架构设计（架构）",
                "prompt-defaults/GEN_ARCH_ARCH.md", created, skipped, missing, true);
        ensureOne(tenantId, "UI", SessionTaskType.GEN_UI,
                "GEN_UI_UI", "生成UI设计（设计师）",
                "prompt-defaults/GEN_UI_UI.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.REVIEW_PRD_DEV,
                "REVIEW_PRD_DEV", "需求设计PRD评审（研发）",
                "prompt-defaults/REVIEW_PRD_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.REVIEW_PRD_QA,
                "REVIEW_PRD_QA", "需求设计PRD评审（测试）",
                "prompt-defaults/REVIEW_PRD_QA.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.REVIEW_ARCH_DEV,
                "REVIEW_ARCH_DEV", "架构设计评审（研发）",
                "prompt-defaults/REVIEW_ARCH_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.REVIEW_ARCH_QA,
                "REVIEW_ARCH_QA", "架构设计评审（测试）",
                "prompt-defaults/REVIEW_ARCH_QA.md", created, skipped, missing);
        ensureOne(tenantId, "PM", SessionTaskType.REVIEW_UI,
                "REVIEW_UI_PM", "UI设计评审（产品）",
                "prompt-defaults/REVIEW_UI_PM.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.REVIEW_UI_DEV,
                "REVIEW_UI_DEV", "UI设计评审（研发）",
                "prompt-defaults/REVIEW_UI_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.REVIEW_UI_QA,
                "REVIEW_UI_QA", "UI设计评审（测试）",
                "prompt-defaults/REVIEW_UI_QA.md", created, skipped, missing);
        ensureOne(tenantId, "PM", SessionTaskType.REVISE_PRD,
                "REVISE_PRD_PM", "修订需求分析（产品）",
                "prompt-defaults/REVISE_PRD_PM.md", created, skipped, missing);
        ensureOne(tenantId, "ARCH", SessionTaskType.REVISE_ARCH,
                "REVISE_ARCH_ARCH", "修订架构设计（架构）",
                "prompt-defaults/REVISE_ARCH_ARCH.md", created, skipped, missing);
        ensureOne(tenantId, "UI", SessionTaskType.REVISE_UI,
                "REVISE_UI_UI", "修订UI设计（设计师）",
                "prompt-defaults/REVISE_UI_UI.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.GEN_DETAIL,
                "GEN_DETAIL_DEV", "详细设计（研发）",
                "prompt-defaults/GEN_DETAIL_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.GEN_CASES,
                "GEN_CASES_QA", "测试用例（测试）",
                "prompt-defaults/GEN_CASES_QA.md", created, skipped, missing);
        ensureOne(tenantId, "ARCH", SessionTaskType.REVIEW_DETAIL_ARCH,
                "REVIEW_DETAIL_ARCH", "详细设计评审（架构）",
                "prompt-defaults/REVIEW_DETAIL_ARCH.md", created, skipped, missing);
        ensureOne(tenantId, "PM", SessionTaskType.REVIEW_DETAIL_PM,
                "REVIEW_DETAIL_PM", "详细设计评审（产品）",
                "prompt-defaults/REVIEW_DETAIL_PM.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.REVIEW_DETAIL_QA,
                "REVIEW_DETAIL_QA", "详细设计评审（测试）",
                "prompt-defaults/REVIEW_DETAIL_QA.md", created, skipped, missing);
        ensureOne(tenantId, "ARCH", SessionTaskType.REVIEW_CASE_ARCH,
                "REVIEW_CASE_ARCH", "测试用例评审（架构）",
                "prompt-defaults/REVIEW_CASE_ARCH.md", created, skipped, missing);
        ensureOne(tenantId, "PM", SessionTaskType.REVIEW_CASE_PM,
                "REVIEW_CASE_PM", "测试用例评审（产品）",
                "prompt-defaults/REVIEW_CASE_PM.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.REVIEW_CASE_DEV,
                "REVIEW_CASE_DEV", "测试用例评审（研发）",
                "prompt-defaults/REVIEW_CASE_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.REVISE_DETAIL,
                "REVISE_DETAIL_DEV", "修订详细设计（研发）",
                "prompt-defaults/REVISE_DETAIL_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.REVISE_CASES,
                "REVISE_CASES_QA", "修订测试用例（测试）",
                "prompt-defaults/REVISE_CASES_QA.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.GEN_CODING,
                "GEN_CODING_DEV", "开发编码（研发）",
                "prompt-defaults/GEN_CODING_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "DEV", SessionTaskType.GEN_CODE_REVIEW,
                "GEN_CODE_REVIEW_DEV", "代码评审（研发）",
                "prompt-defaults/GEN_CODE_REVIEW_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.GEN_INT_TEST,
                "GEN_INT_TEST_QA", "集成测试（测试）",
                "prompt-defaults/GEN_INT_TEST_QA.md", created, skipped, missing, true);
        ensureOne(tenantId, "DEV", SessionTaskType.GEN_ISSUE_ANALYSIS,
                "GEN_ISSUE_ANALYSIS_DEV", "问题分析（研发）",
                "prompt-defaults/GEN_ISSUE_ANALYSIS_DEV.md", created, skipped, missing, true);
        ensureOne(tenantId, "DEV", SessionTaskType.GEN_ISSUE_FIX,
                "GEN_ISSUE_FIX_DEV", "问题修复（研发）",
                "prompt-defaults/GEN_ISSUE_FIX_DEV.md", created, skipped, missing, true); // replace: 确认走对话 JSON
        ensureOne(tenantId, "DEV", SessionTaskType.GEN_ISSUE_CODE_REVIEW,
                "GEN_ISSUE_CODE_REVIEW_DEV", "问题代码评审（研发）",
                "prompt-defaults/GEN_ISSUE_CODE_REVIEW_DEV.md", created, skipped, missing);
        ensureOne(tenantId, "QA", SessionTaskType.GEN_ISSUE_INT_TEST,
                "GEN_ISSUE_INT_TEST_QA", "问题集成测试（测试）",
                "prompt-defaults/GEN_ISSUE_INT_TEST_QA.md", created, skipped, missing, true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("missingPositions", missing);
        return result;
    }

    private void ensureOne(
            Long tenantId,
            String positionCode,
            SessionTaskType taskType,
            String promptCode,
            String promptName,
            String classpathMd,
            List<String> created,
            List<String> skipped,
            List<String> missing) {
        ensureOne(tenantId, positionCode, taskType, promptCode, promptName, classpathMd,
                created, skipped, missing, false);
    }

    private void ensureOne(
            Long tenantId,
            String positionCode,
            SessionTaskType taskType,
            String promptCode,
            String promptName,
            String classpathMd,
            List<String> created,
            List<String> skipped,
            List<String> missing,
            boolean replaceContent) {
        SysPosition position = positionService.findByCode(positionCode);
        if (position == null) {
            missing.add(positionCode);
            return;
        }
        SysPrompt existing = promptMapper.selectOne(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getTenantId, tenantId)
                .eq(SysPrompt::getPositionId, position.getId())
                .eq(SysPrompt::getTaskTypeCode, taskType.name())
                .last("LIMIT 1"));
        if (existing != null) {
            if (replaceContent) {
                existing.setContent(loadDefaultPrompt(classpathMd));
                existing.setName(promptName);
                existing.setUpdatedAt(LocalDateTime.now());
                promptMapper.updateById(existing);
                created.add(existing.getCode() + "(updated)");
            } else {
                skipped.add(positionCode + "+" + taskType.name());
            }
            return;
        }
        // code 冲突则换一个
        String code = promptCode;
        if (promptMapper.selectCount(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getTenantId, tenantId)
                .eq(SysPrompt::getCode, code)) > 0) {
            code = promptCode + "_" + position.getId();
        }
        LocalDateTime now = LocalDateTime.now();
        SysPrompt row = new SysPrompt();
        row.setTenantId(tenantId);
        row.setCode(code);
        row.setName(promptName);
        row.setPositionId(position.getId());
        row.setTaskTypeCode(taskType.name());
        row.setContent(loadDefaultPrompt(classpathMd));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        promptMapper.insert(row);
        created.add(code);
    }

    private String loadDefaultPrompt(String classpath) {
        try {
            ClassPathResource res = new ClassPathResource(classpath);
            if (!res.exists()) {
                return "";
            }
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("load default prompt failed path={}: {}", classpath, e.getMessage());
            return "";
        }
    }

    public List<PromptVO> list() {
        Long tenantId = TenantSupport.requireTenantId();
        return promptMapper.selectList(new LambdaQueryWrapper<SysPrompt>()
                        .eq(SysPrompt::getTenantId, tenantId)
                        .orderByAsc(SysPrompt::getTaskTypeCode)
                        .orderByAsc(SysPrompt::getPositionId)
                        .orderByAsc(SysPrompt::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    public PromptVO get(Long id) {
        return toVO(requireOwned(id));
    }

    /**
     * 工作台任务会话：按岗位 + 任务类型取提示词。
     */
    public PromptVO findByPositionAndTaskType(Long positionId, String taskTypeCode) {
        if (positionId == null || !StringUtils.hasText(taskTypeCode)) {
            return null;
        }
        Long tenantId = TenantSupport.requireTenantId();
        SysPrompt row = promptMapper.selectOne(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getTenantId, tenantId)
                .eq(SysPrompt::getPositionId, positionId)
                .eq(SysPrompt::getTaskTypeCode, taskTypeCode.trim().toUpperCase())
                .last("LIMIT 1"));
        return row == null ? null : toVO(row);
    }

    public SysPrompt findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        Long tenantId = TenantSupport.requireTenantId();
        return promptMapper.selectOne(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getTenantId, tenantId)
                .eq(SysPrompt::getCode, code.trim())
                .last("LIMIT 1"));
    }

    @Transactional
    public Long create(PromptSaveRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        String code = normalizeCode(req.getCode());
        assertCodeUnique(tenantId, code, null);
        SysPosition position = positionService.requireOwned(req.getPositionId());
        SessionTaskType taskType = SessionTaskType.fromCode(req.getTaskTypeCode());
        assertBindingUnique(tenantId, position.getId(), taskType.name(), null);

        LocalDateTime now = LocalDateTime.now();
        SysPrompt row = new SysPrompt();
        row.setTenantId(tenantId);
        row.setCode(code);
        row.setName(req.getName().trim());
        row.setPositionId(position.getId());
        row.setTaskTypeCode(taskType.name());
        row.setContent(req.getContent() == null ? "" : req.getContent());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        promptMapper.insert(row);
        return row.getId();
    }

    @Transactional
    public void update(Long id, PromptSaveRequest req) {
        SysPrompt row = requireOwned(id);
        String code = normalizeCode(req.getCode());
        assertCodeUnique(row.getTenantId(), code, id);
        SysPosition position = positionService.requireOwned(req.getPositionId());
        SessionTaskType taskType = SessionTaskType.fromCode(req.getTaskTypeCode());
        assertBindingUnique(row.getTenantId(), position.getId(), taskType.name(), id);

        row.setCode(code);
        row.setName(req.getName().trim());
        row.setPositionId(position.getId());
        row.setTaskTypeCode(taskType.name());
        row.setContent(req.getContent() == null ? "" : req.getContent());
        row.setUpdatedAt(LocalDateTime.now());
        promptMapper.updateById(row);
    }

    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        promptMapper.deleteById(id);
    }

    public SysPrompt requireOwned(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        SysPrompt row = promptMapper.selectById(id);
        if (row == null || !Objects.equals(row.getTenantId(), tenantId)) {
            throw new BizException(404, "提示词不存在");
        }
        return row;
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException("请填写编码");
        }
        return code.trim();
    }

    private void assertCodeUnique(Long tenantId, String code, Long excludeId) {
        Long count = promptMapper.selectCount(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getTenantId, tenantId)
                .eq(SysPrompt::getCode, code)
                .ne(excludeId != null, SysPrompt::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("提示词编码已存在：" + code);
        }
    }

    private void assertBindingUnique(Long tenantId, Long positionId, String taskTypeCode, Long excludeId) {
        Long count = promptMapper.selectCount(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getTenantId, tenantId)
                .eq(SysPrompt::getPositionId, positionId)
                .eq(SysPrompt::getTaskTypeCode, taskTypeCode)
                .ne(excludeId != null, SysPrompt::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("该岗位与任务类型已配置提示词，请勿重复");
        }
    }

    private PromptVO toVO(SysPrompt row) {
        PromptVO vo = new PromptVO();
        vo.setId(row.getId());
        vo.setCode(row.getCode());
        vo.setName(row.getName());
        vo.setPositionId(row.getPositionId());
        vo.setTaskTypeCode(row.getTaskTypeCode());
        vo.setContent(row.getContent());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        if (row.getPositionId() != null) {
            try {
                SysPosition pos = positionService.requireOwned(row.getPositionId());
                vo.setPositionName(pos.getName());
                vo.setPositionCode(pos.getCode());
            } catch (Exception ignored) {
                // 岗位已删时仍返回 id
            }
        }
        if (StringUtils.hasText(row.getTaskTypeCode())) {
            try {
                SessionTaskType t = SessionTaskType.fromCode(row.getTaskTypeCode());
                vo.setTaskTypeName(t.getLabel());
                vo.setTaskTypeKind(t.getKind());
            } catch (Exception ignored) {
                vo.setTaskTypeName(row.getTaskTypeCode());
            }
        }
        return vo;
    }
}
