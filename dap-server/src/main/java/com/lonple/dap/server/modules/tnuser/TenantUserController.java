package com.lonple.dap.server.modules.tnuser;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.sysuser.dto.UserVO;
import com.lonple.dap.server.modules.tnuser.dto.TenantUserSaveRequest;
import com.lonple.dap.server.modules.tnuser.dto.TenantUserUpdateRequest;
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
@RequestMapping("/api/tenant-users")
@RequiredArgsConstructor
public class TenantUserController {

    private final TenantUserService tenantUserService;

    @GetMapping
    @RequirePermission("tenant-user:view")
    public ApiResponse<List<UserVO>> list() {
        return ApiResponse.ok(tenantUserService.list());
    }

    @GetMapping("/candidates")
    @RequirePermission("tenant-user:view")
    public ApiResponse<List<UserVO>> candidates() {
        return ApiResponse.ok(tenantUserService.listCandidates());
    }

    @GetMapping("/roles")
    @RequirePermission("tenant-user:view")
    public ApiResponse<List<Map<String, String>>> roles() {
        return ApiResponse.ok(tenantUserService.listTenantRoles());
    }

    @PostMapping
    @RequirePermission("tenant-user:operate")
    public ApiResponse<Map<String, Long>> add(@Valid @RequestBody TenantUserSaveRequest request) {
        return ApiResponse.ok(Map.of("id", tenantUserService.add(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("tenant-user:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody TenantUserUpdateRequest request) {
        tenantUserService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("tenant-user:operate")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        tenantUserService.remove(id);
        return ApiResponse.ok();
    }
}
