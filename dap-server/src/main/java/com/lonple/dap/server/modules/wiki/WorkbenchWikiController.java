package com.lonple.dap.server.modules.wiki;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.wiki.dto.WikiCreateNodeRequest;
import com.lonple.dap.server.modules.wiki.dto.WikiFileContentVO;
import com.lonple.dap.server.modules.wiki.dto.WikiNodeVO;
import com.lonple.dap.server.modules.wiki.dto.WikiPublishRequest;
import com.lonple.dap.server.modules.wiki.dto.WikiUpdateFileRequest;
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
@RequestMapping("/api/workbench/wiki")
@RequiredArgsConstructor
public class WorkbenchWikiController {

    private final WikiFsService wikiFsService;

    @GetMapping("/tree")
    @RequirePermission("work:access")
    public ApiResponse<List<WikiNodeVO>> tree() {
        return ApiResponse.ok(wikiFsService.listTree());
    }

    @PostMapping("/dirs")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> createDir(@Valid @RequestBody WikiCreateNodeRequest request) {
        return ApiResponse.ok(Map.of(
                "id",
                wikiFsService.createDirectory(request.getParentId(), request.getDisplayName())));
    }

    @PostMapping("/files")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> createFile(@Valid @RequestBody WikiCreateNodeRequest request) {
        return ApiResponse.ok(Map.of(
                "id",
                wikiFsService.createMarkdownFile(
                        request.getParentId(), request.getDisplayName(), request.getContent())));
    }

    @GetMapping("/nodes/{nodeId}/content")
    @RequirePermission("work:access")
    public ApiResponse<WikiFileContentVO> fileContent(@PathVariable Long nodeId) {
        return ApiResponse.ok(wikiFsService.readFile(nodeId));
    }

    @PutMapping("/nodes/{nodeId}/content")
    @RequirePermission("work:access")
    public ApiResponse<Void> updateFileContent(
            @PathVariable Long nodeId,
            @RequestBody WikiUpdateFileRequest request) {
        wikiFsService.updateFileContent(nodeId, request == null ? null : request.getContent());
        return ApiResponse.ok();
    }

    @DeleteMapping("/nodes/{nodeId}")
    @RequirePermission("work:access")
    public ApiResponse<Void> deleteNode(@PathVariable Long nodeId) {
        wikiFsService.deleteNode(nodeId);
        return ApiResponse.ok();
    }

    @PostMapping("/publish")
    @RequirePermission("work:access")
    public ApiResponse<Map<String, Long>> publish(@Valid @RequestBody WikiPublishRequest request) {
        return ApiResponse.ok(Map.of(
                "id",
                wikiFsService.publishFromPersonal(
                        request.getSourceWorkspaceId(),
                        request.getSourceNodeId(),
                        request.getTargetParentId())));
    }
}
