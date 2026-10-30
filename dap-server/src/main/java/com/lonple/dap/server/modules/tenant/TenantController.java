package com.lonple.dap.server.modules.tenant;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.tenant.dto.TenantSaveRequest;
import com.lonple.dap.server.modules.tenant.dto.TenantVO;
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
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    @RequirePermission("tenant:view")
    public ApiResponse<List<TenantVO>> list() {
        return ApiResponse.ok(tenantService.list());
    }

    @GetMapping("/{id}")
    @RequirePermission("tenant:view")
    public ApiResponse<TenantVO> get(@PathVariable Long id) {
        return ApiResponse.ok(tenantService.get(id));
    }

    @PostMapping
    @RequirePermission("tenant:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody TenantSaveRequest request) {
        return ApiResponse.ok(Map.of("id", tenantService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("tenant:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody TenantSaveRequest request) {
        tenantService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("tenant:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tenantService.delete(id);
        return ApiResponse.ok();
    }
}
