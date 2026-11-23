package com.lonple.dap.server.modules.position;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.position.dto.PositionSaveRequest;
import com.lonple.dap.server.modules.position.dto.PositionVO;
import com.lonple.dap.server.modules.position.dto.SkillSaveRequest;
import com.lonple.dap.server.modules.position.dto.SkillVO;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.RequirePermission;
import com.lonple.dap.server.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionService positionService;

    @GetMapping
    public ApiResponse<List<PositionVO>> list(
            @RequestParam(value = "withSkills", defaultValue = "true") boolean withSkills) {
        assertCanViewPositions();
        return ApiResponse.ok(positionService.list(withSkills));
    }

    @GetMapping("/{id}")
    public ApiResponse<PositionVO> get(@PathVariable Long id) {
        assertCanViewPositions();
        return ApiResponse.ok(positionService.get(id));
    }

    @PostMapping("/ensure-builtin")
    @RequirePermission("position:operate")
    public ApiResponse<List<PositionVO>> ensureBuiltins() {
        return ApiResponse.ok(positionService.ensureBuiltins());
    }

    @PostMapping
    @RequirePermission("position:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody PositionSaveRequest request) {
        return ApiResponse.ok(Map.of("id", positionService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("position:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody PositionSaveRequest request) {
        positionService.update(id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("position:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        positionService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/skills")
    public ApiResponse<List<SkillVO>> listSkills(@PathVariable Long id) {
        assertCanViewPositions();
        return ApiResponse.ok(positionService.listSkills(id));
    }

    private void assertCanViewPositions() {
        LoginUser user = UserContext.require();
        if (!user.getPermissions().contains("position:view")
                && !user.getPermissions().contains("prompt:view")
                && !user.getPermissions().contains("work:access")) {
            throw new BizException(403, "无权限查看岗位");
        }
    }

    @PostMapping("/{id}/skills")
    @RequirePermission("position:operate")
    public ApiResponse<Map<String, Long>> createSkill(
            @PathVariable Long id, @Valid @RequestBody SkillSaveRequest request) {
        return ApiResponse.ok(Map.of("id", positionService.createSkill(id, request)));
    }

    @PutMapping("/{id}/skills/{skillId}")
    @RequirePermission("position:operate")
    public ApiResponse<Void> updateSkill(
            @PathVariable Long id,
            @PathVariable Long skillId,
            @Valid @RequestBody SkillSaveRequest request) {
        positionService.updateSkill(id, skillId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    @RequirePermission("position:operate")
    public ApiResponse<Void> deleteSkill(@PathVariable Long id, @PathVariable Long skillId) {
        positionService.deleteSkill(id, skillId);
        return ApiResponse.ok();
    }
}
