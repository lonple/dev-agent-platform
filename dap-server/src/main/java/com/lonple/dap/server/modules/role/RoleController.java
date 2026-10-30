package com.lonple.dap.server.modules.role;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.role.dto.RoleSaveRequest;
import com.lonple.dap.server.modules.role.entity.SysRole;
import com.lonple.dap.server.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @RequirePermission("role:view")
    public ApiResponse<List<SysRole>> list() {
        return ApiResponse.ok(roleService.list());
    }

    @PostMapping
    @RequirePermission("role:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody RoleSaveRequest request) {
        return ApiResponse.ok(Map.of("id", roleService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("role:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveRequest request) {
        roleService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("role:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/permissions")
    @RequirePermission("role:view")
    public ApiResponse<List<String>> permissions(@PathVariable Long id) {
        return ApiResponse.ok(roleService.getPermissionCodes(id));
    }

    @PutMapping("/{id}/permissions")
    @RequirePermission("role:operate")
    public ApiResponse<Void> savePermissions(@PathVariable Long id, @RequestBody List<String> codes) {
        roleService.savePermissions(id, codes);
        return ApiResponse.ok();
    }
}
