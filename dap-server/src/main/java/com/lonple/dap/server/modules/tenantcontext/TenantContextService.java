package com.lonple.dap.server.modules.tenantcontext;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.position.entity.SysPositionSkill;
import com.lonple.dap.server.modules.tenantcontext.dto.TenantContextUpdateRequest;
import com.lonple.dap.server.modules.tenantcontext.dto.TenantContextVO;
import com.lonple.dap.server.modules.tenantcontext.entity.SysUserTenantContext;
import com.lonple.dap.server.modules.tenantcontext.entity.SysUserTenantContextSkill;
import com.lonple.dap.server.modules.tenantcontext.mapper.UserTenantContextMapper;
import com.lonple.dap.server.modules.tenantcontext.mapper.UserTenantContextSkillMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TenantContextService {

    private final UserTenantContextMapper contextMapper;
    private final UserTenantContextSkillMapper contextSkillMapper;
    private final PositionService positionService;

    public TenantContextVO get() {
        return toVO(findCurrent());
    }

    @Transactional
    public TenantContextVO update(TenantContextUpdateRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();

        Long positionId = req.getPositionId();
        if (positionId == null) {
            throw new BizException("请选择岗位");
        }
        SysPosition position = positionService.requireOwned(positionId);

        List<Long> skillIds = normalizeSkillIds(req.getSkillIds());
        String custom = StringUtils.hasText(req.getCustomSkillText())
                ? req.getCustomSkillText().trim()
                : null;

        if (skillIds.isEmpty() && custom == null) {
            throw new BizException("请至少选择一个技能，或填写自定义技能");
        }

        List<SysPositionSkill> skills = new ArrayList<>();
        for (Long skillId : skillIds) {
            SysPositionSkill skill = positionService.requireSkillOwned(skillId);
            if (!Objects.equals(skill.getPositionId(), positionId)) {
                throw new BizException("技能不属于该岗位");
            }
            skills.add(skill);
        }

        SysUserTenantContext ctx = findCurrent();
        if (ctx == null) {
            ctx = new SysUserTenantContext();
            ctx.setTenantId(tenantId);
            ctx.setUserId(userId);
            ctx.setPositionId(positionId);
            ctx.setCustomSkillText(custom);
            ctx.setUpdatedAt(LocalDateTime.now());
            contextMapper.insert(ctx);
        } else {
            ctx.setPositionId(positionId);
            ctx.setCustomSkillText(custom);
            ctx.setUpdatedAt(LocalDateTime.now());
            contextMapper.updateById(ctx);
            contextSkillMapper.deleteByContextId(ctx.getId());
        }

        for (Long skillId : skillIds) {
            contextSkillMapper.insertLink(ctx.getId(), skillId);
        }

        return buildVo(position, skills, custom);
    }

    public SysUserTenantContext requireReady() {
        SysUserTenantContext ctx = findCurrent();
        if (ctx == null || ctx.getPositionId() == null) {
            throw new BizException(400, "请先选择岗位");
        }
        return ctx;
    }

    /** 可选上下文；未选岗位时返回 null（聊天等不再强制岗位） */
    public SysUserTenantContext findOptional() {
        return findCurrent();
    }

    public List<SysPositionSkill> listContextSkills(Long contextId) {
        List<SysUserTenantContextSkill> links = contextSkillMapper.listByContextId(contextId);
        List<SysPositionSkill> skills = new ArrayList<>();
        for (SysUserTenantContextSkill link : links) {
            try {
                skills.add(positionService.requireSkillOwned(link.getSkillId()));
            } catch (BizException ignored) {
                // skip orphan
            }
        }
        return skills;
    }

    private SysUserTenantContext findCurrent() {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        return contextMapper.selectOne(new LambdaQueryWrapper<SysUserTenantContext>()
                .eq(SysUserTenantContext::getTenantId, tenantId)
                .eq(SysUserTenantContext::getUserId, userId));
    }

    private TenantContextVO toVO(SysUserTenantContext ctx) {
        if (ctx == null) {
            return new TenantContextVO();
        }
        SysPosition position = null;
        if (ctx.getPositionId() != null) {
            try {
                position = positionService.requireOwned(ctx.getPositionId());
            } catch (BizException ignored) {
                return new TenantContextVO();
            }
        }
        List<SysPositionSkill> skills = listContextSkills(ctx.getId());
        return buildVo(position, skills, ctx.getCustomSkillText());
    }

    private TenantContextVO buildVo(SysPosition position, List<SysPositionSkill> skills, String custom) {
        TenantContextVO vo = new TenantContextVO();
        if (position != null) {
            vo.setPositionId(position.getId());
            vo.setPositionName(position.getName());
        }
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (SysPositionSkill skill : skills) {
            ids.add(skill.getId());
            names.add(skill.getName());
        }
        vo.setSkillIds(ids);
        vo.setSkillNames(names);
        vo.setCustomSkillText(custom);
        return vo;
    }

    private static List<Long> normalizeSkillIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(raw.stream().filter(Objects::nonNull).toList()));
    }
}
