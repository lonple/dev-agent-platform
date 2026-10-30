package com.lonple.dap.server.modules.auth;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.auth.dto.LoginRequest;
import com.lonple.dap.server.modules.auth.dto.LoginResponse;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<LoginResponse> me() {
        return ApiResponse.ok(authService.me());
    }

    @GetMapping("/my-tenants")
    public ApiResponse<List<SysTenant>> myTenants() {
        return ApiResponse.ok(authService.myTenants());
    }
}
