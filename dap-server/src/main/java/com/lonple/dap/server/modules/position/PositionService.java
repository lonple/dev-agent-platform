package com.lonple.dap.server.modules.position;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.position.dto.PositionSaveRequest;
import com.lonple.dap.server.modules.position.dto.PositionVO;
import com.lonple.dap.server.modules.position.dto.SkillSaveRequest;
import com.lonple.dap.server.modules.position.dto.SkillVO;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.position.entity.SysPositionSkill;
import com.lonple.dap.server.modules.position.mapper.PositionMapper;
import com.lonple.dap.server.modules.position.mapper.PositionSkillMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionMapper positionMapper;
    private final PositionSkillMapper skillMapper;

    public List<PositionVO> list(boolean withSkills) {
        Long tenantId = TenantSupport.requireTenantId();
        List<SysPosition> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SysPosition>()
                        .eq(SysPosition::getTenantId, tenantId)
                        .orderByAsc(SysPosition::getId));
        if (!withSkills || positions.isEmpty()) {
            return positions.stream().map(p -> toVO(p, List.of())).toList();
        }
        List<Long> ids = positions.stream().map(SysPosition::getId).toList();
        List<SysPositionSkill> skills = skillMapper.selectList(
                new LambdaQueryWrapper<SysPositionSkill>()
                        .in(SysPositionSkill::getPositionId, ids)
                        .orderByAsc(SysPositionSkill::getSortOrder)
                        .orderByAsc(SysPositionSkill::getId));
        Map<Long, List<SysPositionSkill>> byPos = skills.stream()
                .collect(Collectors.groupingBy(SysPositionSkill::getPositionId));
        return positions.stream()
                .map(p -> toVO(p, byPos.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    public PositionVO get(Long id) {
        SysPosition position = requireOwned(id);
        List<SysPositionSkill> skills = skillMapper.selectList(
                new LambdaQueryWrapper<SysPositionSkill>()
                        .eq(SysPositionSkill::getPositionId, id)
                        .orderByAsc(SysPositionSkill::getSortOrder)
                        .orderByAsc(SysPositionSkill::getId));
        return toVO(position, skills);
    }

    @Transactional
    public Long create(PositionSaveRequest req) {
        Long tenantId = TenantSupport.requireTenantId();
        assertNameUnique(tenantId, req.getName(), null);
        String code = normalizeCode(req.getCode());
        assertCodeUnique(tenantId, code, null);
        SysPosition position = new SysPosition();
        position.setTenantId(tenantId);
        position.setCode(code);
        position.setName(req.getName().trim());
        position.setDescription(req.getDescription());
        position.setCreatedAt(LocalDateTime.now());
        position.setUpdatedAt(LocalDateTime.now());
        positionMapper.insert(position);
        return position.getId();
    }

    @Transactional
    public void update(Long id, PositionSaveRequest req) {
        SysPosition position = requireOwned(id);
        assertNameUnique(position.getTenantId(), req.getName(), id);
        String code = normalizeCode(req.getCode());
        assertCodeUnique(position.getTenantId(), code, id);
        position.setCode(code);
        position.setName(req.getName().trim());
        position.setDescription(req.getDescription());
        position.setUpdatedAt(LocalDateTime.now());
        positionMapper.updateById(position);
    }

    public SysPosition findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Long tenantId = TenantSupport.requireTenantId();
        return positionMapper.selectOne(new LambdaQueryWrapper<SysPosition>()
                .eq(SysPosition::getTenantId, tenantId)
                .eq(SysPosition::getCode, code.trim().toUpperCase())
                .last("LIMIT 1"));
    }

    /**
     * 按编码确保内置岗位存在（如 UI 设计师）；已存在则原样返回，不改名。
     */
    @Transactional
    public SysPosition ensureBuiltin(String code, String defaultName) {
        SysPosition existing = findByCode(code);
        if (existing != null) {
            return existing;
        }
        Long tenantId = TenantSupport.requireTenantId();
        String normalized = code == null ? "" : code.trim().toUpperCase();
        SysPosition position = new SysPosition();
        position.setTenantId(tenantId);
        position.setCode(normalized);
        position.setName(defaultName == null || defaultName.isBlank() ? normalized : defaultName.trim());
        position.setDescription("系统初始化流水线提示词时自动创建");
        position.setCreatedAt(LocalDateTime.now());
        position.setUpdatedAt(LocalDateTime.now());
        positionMapper.insert(position);
        return position;
    }

    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        skillMapper.delete(new LambdaQueryWrapper<SysPositionSkill>()
                .eq(SysPositionSkill::getPositionId, id));
        positionMapper.deleteById(id);
    }

    public List<SkillVO> listSkills(Long positionId) {
        requireOwned(positionId);
        return skillMapper.selectList(
                        new LambdaQueryWrapper<SysPositionSkill>()
                                .eq(SysPositionSkill::getPositionId, positionId)
                                .orderByAsc(SysPositionSkill::getSortOrder)
                                .orderByAsc(SysPositionSkill::getId))
                .stream()
                .map(this::toSkillVO)
                .toList();
    }

    @Transactional
    public Long createSkill(Long positionId, SkillSaveRequest req) {
        requireOwned(positionId);
        assertSkillNameUnique(positionId, req.getName(), null);
        SysPositionSkill skill = new SysPositionSkill();
        skill.setPositionId(positionId);
        skill.setName(req.getName().trim());
        skill.setDescription(req.getDescription());
        skill.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.insert(skill);
        return skill.getId();
    }

    @Transactional
    public void updateSkill(Long positionId, Long skillId, SkillSaveRequest req) {
        requireOwned(positionId);
        SysPositionSkill skill = requireSkill(positionId, skillId);
        assertSkillNameUnique(positionId, req.getName(), skillId);
        skill.setName(req.getName().trim());
        skill.setDescription(req.getDescription());
        if (req.getSortOrder() != null) {
            skill.setSortOrder(req.getSortOrder());
        }
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.updateById(skill);
    }

    @Transactional
    public void deleteSkill(Long positionId, Long skillId) {
        requireOwned(positionId);
        requireSkill(positionId, skillId);
        skillMapper.deleteById(skillId);
    }

    public SysPosition requireOwned(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        SysPosition position = positionMapper.selectById(id);
        if (position == null || !Objects.equals(position.getTenantId(), tenantId)) {
            throw new BizException(404, "岗位不存在");
        }
        return position;
    }

    public SysPositionSkill requireSkillOwned(Long skillId) {
        SysPositionSkill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BizException(404, "技能不存在");
        }
        requireOwned(skill.getPositionId());
        return skill;
    }

    private SysPositionSkill requireSkill(Long positionId, Long skillId) {
        SysPositionSkill skill = skillMapper.selectById(skillId);
        if (skill == null || !Objects.equals(skill.getPositionId(), positionId)) {
            throw new BizException(404, "技能不存在");
        }
        return skill;
    }

    private void assertNameUnique(Long tenantId, String name, Long excludeId) {
        Long count = positionMapper.selectCount(new LambdaQueryWrapper<SysPosition>()
                .eq(SysPosition::getTenantId, tenantId)
                .eq(SysPosition::getName, name.trim())
                .ne(excludeId != null, SysPosition::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("岗位名称已存在");
        }
    }

    private void assertSkillNameUnique(Long positionId, String name, Long excludeId) {
        Long count = skillMapper.selectCount(new LambdaQueryWrapper<SysPositionSkill>()
                .eq(SysPositionSkill::getPositionId, positionId)
                .eq(SysPositionSkill::getName, name.trim())
                .ne(excludeId != null, SysPositionSkill::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("技能名称已存在");
        }
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase();
    }

    private void assertCodeUnique(Long tenantId, String code, Long excludeId) {
        if (code == null) {
            return;
        }
        Long count = positionMapper.selectCount(new LambdaQueryWrapper<SysPosition>()
                .eq(SysPosition::getTenantId, tenantId)
                .eq(SysPosition::getCode, code)
                .ne(excludeId != null, SysPosition::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("岗位编码已存在：" + code);
        }
    }

    private PositionVO toVO(SysPosition position, List<SysPositionSkill> skills) {
        PositionVO vo = new PositionVO();
        vo.setId(position.getId());
        vo.setCode(position.getCode());
        vo.setName(position.getName());
        vo.setDescription(position.getDescription());
        vo.setCreatedAt(position.getCreatedAt());
        vo.setUpdatedAt(position.getUpdatedAt());
        List<SkillVO> skillVos = new ArrayList<>();
        for (SysPositionSkill skill : skills) {
            skillVos.add(toSkillVO(skill));
        }
        vo.setSkills(skillVos);
        return vo;
    }

    private SkillVO toSkillVO(SysPositionSkill skill) {
        SkillVO vo = new SkillVO();
        vo.setId(skill.getId());
        vo.setPositionId(skill.getPositionId());
        vo.setName(skill.getName());
        vo.setDescription(skill.getDescription());
        vo.setSortOrder(skill.getSortOrder());
        vo.setCreatedAt(skill.getCreatedAt());
        vo.setUpdatedAt(skill.getUpdatedAt());
        return vo;
    }
}
