package com.lonple.dap.server.modules.role;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.role.dto.RoleSaveRequest;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.modules.role.mapper.RoleMapper;
import com.lonple.dap.server.modules.role.mapper.RolePermissionMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleService {

    private static final String FORBIDDEN_PERM = "permission:operate";

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final SysUserMapper userMapper;

    public List<SysRole> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    @Transactional
    public Long create(RoleSaveRequest req) {
        assertNameUnique(req.getName(), null);
        SysRole role = new SysRole();
        role.setName(req.getName());
        role.setDescription(req.getDescription());
        role.setBuiltin(false);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        return role.getId();
    }

    @Transactional
    public void update(Long id, RoleSaveRequest req) {
        SysRole role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new BizException("内置角色不可修改名称，仅可配置权限");
        }
        assertNameUnique(req.getName(), id);
        role.setName(req.getName());
        role.setDescription(req.getDescription());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);
    }

    @Transactional
    public void delete(Long id) {
        SysRole role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new BizException("内置角色不可删除");
        }
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleId, id));
        if (count != null && count > 0) {
            throw new BizException("仍有用户绑定该角色，无法删除");
        }
        rolePermissionMapper.deleteByRoleId(id);
        roleMapper.deleteById(id);
    }

    public List<String> getPermissionCodes(Long roleId) {
        requireRole(roleId);
        return rolePermissionMapper.selectCodesByRoleId(roleId);
    }

    @Transactional
    public void savePermissions(Long roleId, List<String> codes) {
        requireRole(roleId);
        rolePermissionMapper.deleteByRoleId(roleId);
        if (codes == null) {
            return;
        }
        for (String code : codes) {
            if (FORBIDDEN_PERM.equals(code)) {
                continue;
            }
            rolePermissionMapper.insertByCode(roleId, code);
        }
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException("角色不存在");
        }
        return role;
    }

    private void assertNameUnique(String name, Long excludeId) {
        LambdaQueryWrapper<SysRole> qw = new LambdaQueryWrapper<SysRole>().eq(SysRole::getName, name);
        if (excludeId != null) {
            qw.ne(SysRole::getId, excludeId);
        }
        Long count = roleMapper.selectCount(qw);
        if (count != null && count > 0) {
            throw new BizException("角色名已存在");
        }
    }
}
