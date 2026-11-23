package com.lonple.dap.server.modules.sandbox;

import com.lonple.dap.server.common.ApiResponse;
import lombok.Data;
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
@RequestMapping("/api/work/products/{productId}/sandbox")
@RequiredArgsConstructor
public class SandboxProductController {

    private final SandboxProfileService profileService;

    @PostMapping("/ensure")
    public ApiResponse<Map<String, Object>> ensure(@PathVariable Long productId) {
        return ApiResponse.ok(profileService.ensureDefaultBundle(productId));
    }

    @GetMapping("/resources")
    public ApiResponse<List<Map<String, Object>>> resources(@PathVariable Long productId) {
        return ApiResponse.ok(profileService.listResources(productId));
    }

    @PutMapping("/resources")
    public ApiResponse<Map<String, Object>> upsertResource(
            @PathVariable Long productId, @RequestBody ResourceBody body) {
        SandboxProfileService.ResourceSaveReq req = new SandboxProfileService.ResourceSaveReq();
        req.resourceCode = body.getResourceCode();
        req.image = body.getImage();
        req.maxSlots = body.getMaxSlots();
        req.status = body.getStatus();
        req.nodeId = body.getNodeId();
        return ApiResponse.ok(profileService.upsertResource(productId, req));
    }

    @GetMapping("/profiles")
    public ApiResponse<List<Map<String, Object>>> profiles(@PathVariable Long productId) {
        return ApiResponse.ok(profileService.listProfiles(productId));
    }

    @PutMapping("/profiles")
    public ApiResponse<Map<String, Object>> upsertProfile(
            @PathVariable Long productId, @RequestBody ProfileBody body) {
        SandboxProfileService.ProfileSaveReq req = new SandboxProfileService.ProfileSaveReq();
        req.profileCode = body.getProfileCode();
        req.displayName = body.getDisplayName();
        req.isDefault = body.getIsDefault();
        req.configJson = body.getConfigJson();
        req.status = body.getStatus();
        return ApiResponse.ok(profileService.upsertProfile(productId, req));
    }

    @PostMapping("/profiles/{code}/default")
    public ApiResponse<Map<String, Object>> setDefault(
            @PathVariable Long productId, @PathVariable String code) {
        return ApiResponse.ok(profileService.setDefault(productId, code));
    }

    @DeleteMapping("/profiles/{code}")
    public ApiResponse<Void> deleteProfile(@PathVariable Long productId, @PathVariable String code) {
        profileService.deleteProfile(productId, code);
        return ApiResponse.ok(null);
    }

    @GetMapping("/profiles/{code}/preview")
    public ApiResponse<Map<String, Object>> preview(
            @PathVariable Long productId,
            @PathVariable String code,
            @RequestParam(required = false) String reqNo) {
        return ApiResponse.ok(profileService.preview(productId, code, reqNo));
    }

    @Data
    public static class ResourceBody {
        private String resourceCode;
        private String image;
        private Integer maxSlots;
        private String status;
        private Long nodeId;
    }

    @Data
    public static class ProfileBody {
        private String profileCode;
        private String displayName;
        private Boolean isDefault;
        private String configJson;
        private String status;
    }
}
