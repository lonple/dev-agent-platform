package com.lonple.dap.server.modules.tnworkspace;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.agenttool.AgentFsToolService;
import com.lonple.dap.server.modules.chat.WorkspaceSessionService;
import com.lonple.dap.server.modules.chat.dto.ChatSessionVO;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionCreateRequest;
import com.lonple.dap.server.modules.chat.dto.WorkspaceSessionUpdateRequest;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkbenchWorkspaceCreateRequest;
import com.lonple.dap.server.modules.tnworkspace.dto.TenantWorkspaceVO;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceCreateNodeRequest;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceFileContentVO;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceNodeVO;
import com.lonple.dap.server.modules.tnworkspace.dto.WorkspaceUpdateFileRequest;
import com.lonple.dap.server.security.RequirePermission;
import com.lonple.dap.server.security.UserContext;
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
@RequestMapping("/api/workbench/workspaces")
@RequiredArgsConstructor
public class WorkbenchWorkspaceController {

    private final TenantWorkspaceService tenantWorkspaceService;
    private final WorkspaceFsService workspaceFsService;
    private final WorkspaceSessionService workspaceSessionService;
    private final AgentFsToolService agentFsToolService;

    @GetMapping("/personal")
    @RequirePermission("work:access")
    public ApiResponse<List<TenantWorkspaceVO>> personal() {
        return ApiResponse.ok(tenantWorkspaceService.listPersonalSide());
    }

    @PostMapping
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> createPersonal(@Valid @RequestBody WorkbenchWorkspaceCreateRequest request) {
        return ApiResponse.ok(Map.of("id", tenantWorkspaceService.createPersonal(request)));
    }

    @GetMapping("/{id}/tree")
    @RequirePermission("work:access")
    public ApiResponse<List<WorkspaceNodeVO>> tree(@PathVariable Long id) {
        return ApiResponse.ok(workspaceFsService.listTree(id));
    }

    @PostMapping("/{id}/dirs")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> createDir(
            @PathVariable Long id,
            @Valid @RequestBody WorkspaceCreateNodeRequest request) {
        return ApiResponse.ok(Map.of(
                "id",
                workspaceFsService.createDirectory(id, request.getParentId(), request.getDisplayName())));
    }

    @PostMapping("/{id}/files")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> createFile(
            @PathVariable Long id,
            @Valid @RequestBody WorkspaceCreateNodeRequest request) {
        return ApiResponse.ok(Map.of(
                "id",
                workspaceFsService.createMarkdownFile(
                        id, request.getParentId(), request.getDisplayName(), request.getContent())));
    }

    @GetMapping("/{id}/nodes/{nodeId}/content")
    @RequirePermission("work:access")
    public ApiResponse<WorkspaceFileContentVO> fileContent(
            @PathVariable Long id,
            @PathVariable Long nodeId) {
        return ApiResponse.ok(workspaceFsService.readFile(id, nodeId));
    }

    @PutMapping("/{id}/nodes/{nodeId}/content")
    @RequirePermission("work:access")
    public ApiResponse<Void> updateFileContent(
            @PathVariable Long id,
            @PathVariable Long nodeId,
            @RequestBody WorkspaceUpdateFileRequest request) {
        workspaceFsService.updateFileContent(id, nodeId, request == null ? null : request.getContent());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/nodes/{nodeId}")
    @RequirePermission("work:access")
    public ApiResponse<Void> deleteNode(@PathVariable Long id, @PathVariable Long nodeId) {
        workspaceFsService.deleteNode(id, nodeId);
        return ApiResponse.ok();
    }

    @GetMapping("/session-task-types")
    public ApiResponse<List<?>> sessionTaskTypes() {
        var permissions = UserContext.require().getPermissions();
        if (!permissions.contains("work:access") && !permissions.contains("prompt:view")) {
            throw new BizException(403, "无权限查看任务类型");
        }
        return ApiResponse.ok(workspaceSessionService.listTaskTypeOptions());
    }

    @GetMapping("/{id}/sessions")
    @RequirePermission("work:access")
    public ApiResponse<List<ChatSessionVO>> sessions(@PathVariable Long id) {
        return ApiResponse.ok(workspaceSessionService.listByWorkspace(id));
    }

    @PostMapping("/{id}/sessions")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> createSession(
            @PathVariable Long id, @Valid @RequestBody WorkspaceSessionCreateRequest request) {
        return ApiResponse.ok(Map.of("id", workspaceSessionService.create(id, request)));
    }

    @PutMapping("/{id}/sessions/{sessionId}")
    @RequirePermission("work:access")
    public ApiResponse<Void> updateSession(
            @PathVariable Long id,
            @PathVariable Long sessionId,
            @Valid @RequestBody WorkspaceSessionUpdateRequest request) {
        workspaceSessionService.update(id, sessionId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    @RequirePermission("work:access")
    public ApiResponse<Void> deleteSession(@PathVariable Long id, @PathVariable Long sessionId) {
        workspaceSessionService.delete(id, sessionId);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/sessions/{sessionId}")
    @RequirePermission("work:access")
    public ApiResponse<ChatSessionVO> getSession(@PathVariable Long id, @PathVariable Long sessionId) {
        return ApiResponse.ok(workspaceSessionService.get(id, sessionId));
    }

    @GetMapping("/{id}/nodes/{nodeId}/revisions")
    @RequirePermission("work:access")
    public ApiResponse<List<Map<String, Object>>> listRevisions(
            @PathVariable Long id, @PathVariable Long nodeId) {
        workspaceFsService.readFile(id, nodeId); // 权限校验
        return ApiResponse.ok(agentFsToolService.listRevisions(id, nodeId));
    }

    @GetMapping("/{id}/nodes/{nodeId}/diff")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Object>> fileDiff(
            @PathVariable Long id,
            @PathVariable Long nodeId,
            @RequestParam(required = false) Integer versionNo) {
        workspaceFsService.readFile(id, nodeId); // 权限校验
        return ApiResponse.ok(agentFsToolService.getDiff(id, nodeId, versionNo));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("work:access")
    public ApiResponse<Void> deletePersonal(@PathVariable Long id) {
        tenantWorkspaceService.deletePersonal(id);
        return ApiResponse.ok();
    }
}
