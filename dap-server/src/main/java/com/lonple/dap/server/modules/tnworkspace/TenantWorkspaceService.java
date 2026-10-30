package com.lonple.dap.server.modules.tnworkspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tnworkspace.dto.TenantWorkspaceVO;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkbenchWorkspaceCreateRequest;
import com.lonple.dap.server.modules.tnworkspace.entity.SysWorkspace;
import com.lonple.dap.server.modules.tnworkspace.mapper.SysWorkspaceMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantWorkspaceService {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    private final SysWorkspaceMapper workspaceMapper;
    private final SysUserMapper userMapper;
    private final WorkspaceFsService workspaceFsService;

    /** 工作台：仅当前用户的个人工作空间 */
    public List<TenantWorkspaceVO> listPersonalSide() {
        Long tenantId = TenantSupport.requireTenantId();
        Long userId = TenantSupport.requireUserId();
        return workspaceMapper.selectList(new LambdaQueryWrapper<SysWorkspace>()
                        .eq(SysWorkspace::getTenantId, tenantId)
                        .eq(SysWorkspace::getSpaceType, WorkspaceSpaceType.PERSONAL.name())
                        .eq(SysWorkspace::getOwnerUserId, userId)
                        .orderByDesc(SysWorkspace::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 工作台创建个人工作空间 */
    @Transactional
    public Long createPersonal(WorkbenchWorkspaceCreateRequest req) {
        Long userId = TenantSupport.requireUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("当前用户不存在");
        }
        String name = req.getName().trim();
        Long tenantId = TenantSupport.requireTenantId();
        assertNameUnique(tenantId, name, null);
        String workDir = allocateWorkDir(tenantId, userId, name);

        SysWorkspace entity = new SysWorkspace();
        entity.setTenantId(tenantId);
        entity.setSpaceType(WorkspaceSpaceType.PERSONAL.name());
        entity.setName(name);
        entity.setOwnerUserId(userId);
        entity.setWorkDir(workDir);
        entity.setStatus(STATUS_ENABLED);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        workspaceMapper.insert(entity);
        workspaceFsService.ensureWorkspaceDir(entity);
        workspaceFsService.rewriteManifest(entity);
        return entity.getId();
    }

    /** 删除个人工作空间：磁盘目录、文件节点、会话一并清理 */
    @Transactional
    public void deletePersonal(Long id) {
        SysWorkspace entity = requireOwnedPersonal(id);
        workspaceFsService.purgeWorkspace(entity);
        workspaceMapper.deleteById(entity.getId());
    }

    private String allocateWorkDir(Long tenantId, Long ownerUserId, String displayName) {
        String base = normalizeWorkDir(displayName);
        for (int i = 0; i < 32; i++) {
            String candidate = i == 0 ? base : base + "_" + i;
            if (candidate.length() > 64) {
                candidate = candidate.substring(0, 64);
            }
            if (!workDirExists(tenantId, ownerUserId, candidate, null)) {
                return normalizeWorkDir(candidate);
            }
        }
        return normalizeWorkDir("ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private boolean workDirExists(Long tenantId, Long ownerUserId, String workDir, Long excludeId) {
        Long count = workspaceMapper.selectCount(new LambdaQueryWrapper<SysWorkspace>()
                .eq(SysWorkspace::getTenantId, tenantId)
                .eq(SysWorkspace::getSpaceType, WorkspaceSpaceType.PERSONAL.name())
                .eq(SysWorkspace::getOwnerUserId, ownerUserId)
                .eq(SysWorkspace::getWorkDir, workDir)
                .ne(excludeId != null, SysWorkspace::getId, excludeId));
        return count != null && count > 0;
    }

    private SysWorkspace requireOwnedPersonal(Long id) {
        Long userId = TenantSupport.requireUserId();
        SysWorkspace entity = requireInTenant(id);
        if (WorkspaceSpaceType.fromCode(entity.getSpaceType()) != WorkspaceSpaceType.PERSONAL) {
            throw new BizException("只能操作个人工作空间");
        }
        if (!Objects.equals(entity.getOwnerUserId(), userId)) {
            throw new BizException(403, "只能操作自己的个人工作空间");
        }
        return entity;
    }

    private SysWorkspace requireInTenant(Long id) {
        Long tenantId = TenantSupport.requireTenantId();
        SysWorkspace entity = workspaceMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getTenantId(), tenantId)) {
            throw new BizException(404, "工作空间不存在");
        }
        return entity;
    }

    private void assertNameUnique(Long tenantId, String name, Long excludeId) {
        Long count = workspaceMapper.selectCount(new LambdaQueryWrapper<SysWorkspace>()
                .eq(SysWorkspace::getTenantId, tenantId)
                .eq(SysWorkspace::getSpaceType, WorkspaceSpaceType.PERSONAL.name())
                .eq(SysWorkspace::getName, name)
                .ne(excludeId != null, SysWorkspace::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("工作空间名称已存在");
        }
    }

    private String normalizeWorkDir(String workDir) {
        if (!StringUtils.hasText(workDir)) {
            throw new BizException("请输入工作目录");
        }
        String dir = workDir.trim().replace('\\', '/');
        if (dir.contains("/") || dir.contains("..") || dir.contains("\\")) {
            throw new BizException("工作目录只能是单层文件夹名");
        }
        if (!dir.matches("^[a-zA-Z0-9_]+$")) {
            throw new BizException("工作目录仅支持英文、数字、下划线");
        }
        return dir;
    }

    private TenantWorkspaceVO toVO(SysWorkspace entity) {
        WorkspaceSpaceType type = WorkspaceSpaceType.fromCode(entity.getSpaceType());
        TenantWorkspaceVO vo = new TenantWorkspaceVO();
        vo.setId(entity.getId());
        vo.setSpaceType(type.name());
        vo.setSpaceTypeLabel(type.getLabel());
        vo.setName(entity.getName());
        vo.setOwnerUserId(entity.getOwnerUserId());
        vo.setWorkDir(entity.getWorkDir());
        vo.setStatus(entity.getStatus());
        vo.setStatusLabel(Objects.equals(entity.getStatus(), STATUS_ENABLED) ? "启用" : "禁用");
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        if (entity.getOwnerUserId() != null) {
            SysUser user = userMapper.selectById(entity.getOwnerUserId());
            if (user != null) {
                vo.setOwnerUsername(user.getUsername());
            }
        }
        return vo;
    }
}
