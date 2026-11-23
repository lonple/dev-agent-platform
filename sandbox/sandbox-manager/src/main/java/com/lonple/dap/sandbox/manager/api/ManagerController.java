package com.lonple.dap.sandbox.manager.api;

import com.lonple.dap.sandbox.manager.client.SandboxClientHttp;
import com.lonple.dap.sandbox.manager.domain.LeaseRoutes;
import com.lonple.dap.sandbox.manager.domain.NodeRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ManagerController {

    private final NodeRegistry nodeRegistry;
    private final LeaseRoutes leaseRoutes;
    private final SandboxClientHttp clientHttp;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "nodes", nodeRegistry.all().size());
    }

    @GetMapping("/nodes")
    public List<NodeRegistry.NodeRuntime> nodes() {
        return List.copyOf(nodeRegistry.all());
    }

    @PostMapping("/nodes/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        if (!StringUtils.hasText(req.getCode()) || !StringUtils.hasText(req.getClientBaseUrl())) {
            throw new IllegalArgumentException("code and clientBaseUrl required");
        }
        nodeRegistry.upsert(req.getCode(), req.getClientBaseUrl());
        return Map.of("ok", true, "code", req.getCode());
    }

    @PostMapping("/leases/{leaseId}/attach")
    public Map<String, Object> attach(@PathVariable Long leaseId, @RequestBody AttachReq req) {
        String nodeCode = StringUtils.hasText(req.getNodeCode()) ? req.getNodeCode() : "local-dev";
        NodeRegistry.NodeRuntime node = nodeRegistry.require(nodeCode);
        if (!node.isOnline() || !node.isDockerOk()) {
            // still try once; health may be stale at first boot
        }
        Map<String, Object> body = new HashMap<>();
        body.put("leaseId", leaseId);
        body.put("image", req.getImage());
        body.put("worktreeHostPath", req.getWorktreeHostPath());
        body.put("env", req.getEnv() == null ? Map.of() : req.getEnv());
        body.put("portPublishes", req.getPortPublishes() == null ? List.of() : req.getPortPublishes());
        body.put("cacheMounts", req.getCacheMounts() == null ? Map.of() : req.getCacheMounts());
        body.put("labels", req.getLabels() == null ? Map.of() : req.getLabels());
        Map<String, Object> resp = clientHttp.attach(node.getClientBaseUrl(), body);
        leaseRoutes.put(leaseId, node.getCode(), node.getClientBaseUrl());
        Map<String, Object> out = new HashMap<>();
        out.put("leaseId", leaseId);
        out.put("nodeCode", node.getCode());
        out.put("codeToolsReady", true);
        out.put("client", resp);
        return out;
    }

    @PostMapping("/leases/{leaseId}/detach")
    public Map<String, Object> detach(@PathVariable Long leaseId) {
        LeaseRoutes.Route route = leaseRoutes.get(leaseId);
        if (route == null) {
            return Map.of("ok", true, "message", "already detached");
        }
        Map<String, Object> resp = clientHttp.detach(route.getClientBaseUrl(), leaseId);
        leaseRoutes.remove(leaseId);
        return Map.of("ok", true, "client", resp);
    }

    @PostMapping("/leases/{leaseId}/code/{op}")
    public Object code(
            @PathVariable Long leaseId, @PathVariable String op, @RequestBody(required = false) Map<String, Object> body) {
        LeaseRoutes.Route route = leaseRoutes.require(leaseId);
        return clientHttp.code(route.getClientBaseUrl(), leaseId, op, body == null ? Map.of() : body);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        String msg = e.getMessage() == null ? "conflict" : e.getMessage();
        String code = msg.startsWith("SB_") ? msg : "SB_LEASE_NOT_ATTACHED";
        return Map.of("code", code, "message", msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> bad(IllegalArgumentException e) {
        return Map.of("code", "SB_BAD_REQUEST", "message", e.getMessage() == null ? "bad" : e.getMessage());
    }

    @Data
    public static class RegisterReq {
        private String code;
        private String clientBaseUrl;
    }

    @Data
    public static class AttachReq {
        private String nodeCode;
        private String image;
        private String worktreeHostPath;
        private Map<String, String> env;
        private List<Map<String, Object>> portPublishes;
        private Map<String, String> cacheMounts;
        private Map<String, String> labels;
    }
}
