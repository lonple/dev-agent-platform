package com.lonple.dap.server.modules.sysuser;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.PageResult;
import com.lonple.dap.server.modules.sysuser.dto.UserSaveRequest;
import com.lonple.dap.server.modules.sysuser.dto.UserUpdateRequest;
import com.lonple.dap.server.modules.sysuser.dto.UserVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @RequirePermission("user:view")
    public ApiResponse<PageResult<UserVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username) {
        return ApiResponse.ok(userService.page(page, size, username));
    }

    @GetMapping("/all")
    @RequirePermission("tenant:view")
    public ApiResponse<List<UserVO>> all() {
        return ApiResponse.ok(userService.listAll());
    }

    @PostMapping
    @RequirePermission("user:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody UserSaveRequest request) {
        return ApiResponse.ok(Map.of("id", userService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("user:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        userService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("user:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.ok();
    }
}
