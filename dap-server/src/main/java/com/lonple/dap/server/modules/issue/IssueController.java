package com.lonple.dap.server.modules.issue;

import com.lonple.dap.server.common.ApiResponse;
import com.lonple.dap.server.modules.issue.dto.IssueHandlerCandidateVO;
import com.lonple.dap.server.modules.issue.dto.IssueSaveRequest;
import com.lonple.dap.server.modules.issue.dto.IssueStatusRequest;
import com.lonple.dap.server.modules.issue.dto.IssueVO;
import com.lonple.dap.server.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;
    private final IssueAttachmentService attachmentService;

    @GetMapping
    @RequirePermission("issue:view")
    public ApiResponse<List<IssueVO>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(issueService.list(status));
    }

    @GetMapping("/workbench")
    @RequirePermission("work:access")
    public ApiResponse<List<IssueVO>> workbench(
            @RequestParam(defaultValue = "todo") String scope) {
        return ApiResponse.ok(issueService.listWorkbench(scope));
    }

    @GetMapping("/handler-candidates")
    @RequirePermission("issue:view")
    public ApiResponse<List<IssueHandlerCandidateVO>> handlerCandidates() {
        return ApiResponse.ok(issueService.listHandlerCandidates());
    }

    @PostMapping("/attachments")
    @RequirePermission("issue:operate")
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(attachmentService.store(file));
    }

    @GetMapping("/attachments/{fileName}")
    @RequirePermission("issue:view")
    public ResponseEntity<byte[]> download(@PathVariable String fileName) {
        IssueAttachmentService.LoadedFile loaded = attachmentService.load(fileName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(loaded.mediaType())
                .body(loaded.bytes());
    }

    @GetMapping("/{id}")
    @RequirePermission("issue:view")
    public ApiResponse<IssueVO> get(@PathVariable Long id) {
        return ApiResponse.ok(issueService.get(id));
    }

    @PostMapping
    @RequirePermission("issue:operate")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody IssueSaveRequest request) {
        return ApiResponse.ok(Map.of("id", issueService.create(request)));
    }

    @PutMapping("/{id}")
    @RequirePermission("issue:operate")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody IssueSaveRequest request) {
        issueService.update(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/status")
    @RequirePermission("issue:operate")
    public ApiResponse<Void> changeStatus(
            @PathVariable Long id, @Valid @RequestBody IssueStatusRequest request) {
        issueService.changeStatus(id, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("issue:operate")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        issueService.delete(id);
        return ApiResponse.ok();
    }
}
