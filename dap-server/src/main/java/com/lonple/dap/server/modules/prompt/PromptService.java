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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptMapper promptMapper;
    private final PositionService positionService;

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
