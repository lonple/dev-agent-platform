package com.lonple.dap.server.modules.sysuser;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.common.PageResult;
import com.lonple.dap.server.modules.role.PlatformRole;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.modules.role.mapper.RoleMapper;
import com.lonple.dap.server.modules.sysuser.dto.UserSaveRequest;
import com.lonple.dap.server.modules.sysuser.dto.UserUpdateRequest;
import com.lonple.dap.server.modules.sysuser.dto.UserVO;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.modules.tenant.mapper.TenantUserMapper;
import com.lonple.dap.server.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final TenantUserMapper tenantUserMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<UserVO> page(long page, long size, String username) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            qw.like(SysUser::getUsername, username);
        }
        qw.orderByAsc(SysUser::getId);
        Page<SysUser> result = userMapper.selectPage(new Page<>(page, size), qw);
        Map<Long, String> roleNames = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getName, (a, b) -> a));
        List<UserVO> records = result.getRecords().stream()
                .map(u -> toVo(u, roleNames.get(u.getRoleId())))
                .toList();
        return new PageResult<>(result.getTotal(), records);
    }

    public List<UserVO> listAll() {
        Map<Long, String> roleNames = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getName, (a, b) -> a));
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId))
                .stream()
                .map(u -> toVo(u, roleNames.get(u.getRoleId())))
                .toList();
    }

    @Transactional
    public Long create(UserSaveRequest req) {
        PlatformRole.assertPlatformRoleId(req.getRoleId());
        assertRoleExists(req.getRoleId());
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (count != null && count > 0) {
            throw new BizException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setDescription(req.getDescription());
        user.setRoleId(req.getRoleId());
        user.setBuiltin(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user.getId();
    }

    @Transactional
    public void update(Long id, UserUpdateRequest req) {
        SysUser user = requireUser(id);
        if (Boolean.TRUE.equals(user.getBuiltin())) {
            if (!StringUtils.hasText(req.getPassword())) {
                throw new BizException("密码不能为空");
            }
            if (req.getPassword().length() < 6 || req.getPassword().length() > 64) {
                throw new BizException("密码长度需在 6-64 之间");
            }
            user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
            return;
        }
        if (!StringUtils.hasText(req.getPassword())) {
            throw new BizException("密码不能为空");
        }
        if (req.getRoleId() == null) {
            throw new BizException("角色不能为空");
        }
        PlatformRole.assertPlatformRoleId(req.getRoleId());
        assertRoleExists(req.getRoleId());
        if (StringUtils.hasText(req.getUsername()) && !Objects.equals(req.getUsername(), user.getUsername())) {
            Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, req.getUsername())
                    .ne(SysUser::getId, id));
            if (count != null && count > 0) {
                throw new BizException("用户名已存在");
            }
            user.setUsername(req.getUsername());
        }
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setDescription(req.getDescription());
        user.setRoleId(req.getRoleId());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    @Transactional
    public void delete(Long id) {
        SysUser user = requireUser(id);
        if (Boolean.TRUE.equals(user.getBuiltin())) {
            throw new BizException("内置用户不可删除");
        }
        if (Objects.equals(UserContext.require().getUserId(), id)) {
            throw new BizException("不能删除当前登录用户");
        }
        tenantUserMapper.deleteByUserId(id);
        userMapper.deleteById(id);
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    private void assertRoleExists(Long roleId) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException("角色不存在");
        }
    }

    private UserVO toVo(SysUser user, String roleName) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .description(user.getDescription())
                .roleId(user.getRoleId())
                .roleName(roleName)
                .builtin(Boolean.TRUE.equals(user.getBuiltin()))
                .build();
    }
}
