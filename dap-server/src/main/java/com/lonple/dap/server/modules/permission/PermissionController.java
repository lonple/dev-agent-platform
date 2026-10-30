package com.lonple.dap.server.modules.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.role.entity.SysPermission;
import com.lonple.dap.server.modules.role.mapper.PermissionMapper;
import com.lonple.dap.server.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionMapper permissionMapper;

    @GetMapping
    @RequirePermission("permission:view")
    public ApiResponse<List<SysPermission>> list() {
        List<SysPermission> list = permissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId));
        return ApiResponse.ok(list);
    }
}
