package com.lonple.dap.server.modules.tenantcontext;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.tenantcontext.dto.TenantContextUpdateRequest;
import com.lonple.dap.server.modules.tenantcontext.dto.TenantContextVO;
import com.lonple.dap.server.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenant/context")
@RequiredArgsConstructor
public class TenantContextController {

    private final TenantContextService tenantContextService;

    @GetMapping
    @RequirePermission("work:access")
    public ApiResponse<TenantContextVO> get() {
        return ApiResponse.ok(tenantContextService.get());
    }

    @PutMapping
    @RequirePermission("work:access")
    public ApiResponse<TenantContextVO> update(@Valid @RequestBody TenantContextUpdateRequest request) {
        return ApiResponse.ok(tenantContextService.update(request));
    }
}
