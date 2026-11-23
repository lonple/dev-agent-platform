package com.lonple.dap.sandbox.client.api;

import com.lonple.dap.sandbox.client.config.ClientProps;
import com.lonple.dap.sandbox.client.docker.SandboxRuntime;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class ClientController {

    private final SandboxRuntime runtime;
    private final ClientProps props;
    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping({"/health", "/api/v1/health"})
    public Map<String, Object> health() {
        return runtime.health();
    }

    @PostMapping("/api/v1/sandboxes/attach")
    public Map<String, Object> attach(@RequestBody AttachReq req) throws Exception {
        SandboxRuntime.Instance inst = runtime.attach(
                req.getLeaseId(),
                req.getImage(),
                req.getWorktreeHostPath(),
                req.getEnv(),
                req.getPortPublishes(),
                req.getCacheMounts());
        Map<String, Object> out = new HashMap<>();
        out.put("leaseId", inst.getLeaseId());
        out.put("containerName", inst.getContainerName());
        out.put("codeToolsBaseUrl", inst.getCodeToolsBaseUrl());
        return out;
    }

    @PostMapping("/api/v1/sandboxes/{leaseId}/detach")
    public Map<String, Object> detach(@PathVariable Long leaseId) throws Exception {
        runtime.detach(leaseId);
        return Map.of("ok", true, "leaseId", leaseId);
    }

    @PostMapping("/api/v1/sandboxes/{leaseId}/code/{op}")
    public Object code(
            @PathVariable Long leaseId,
            @PathVariable String op,
            @RequestBody(required = false) Map<String, Object> body) {
        SandboxRuntime.Instance inst = runtime.require(leaseId);
        String url = inst.getCodeToolsBaseUrl() + "/" + op;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Sandbox-Token", props.getSharedToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Object> resp = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body == null ? Map.of() : body, headers),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("code-tools " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        String msg = e.getMessage() == null ? "conflict" : e.getMessage();
        String code = msg.startsWith("SB_") ? msg.split(":", 2)[0].trim() : "SB_ATTACH_FAILED";
        return Map.of("code", code, "message", msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> bad(IllegalArgumentException e) {
        return Map.of("code", "SB_BAD_REQUEST", "message", e.getMessage() == null ? "bad" : e.getMessage());
    }

    @Data
    public static class AttachReq {
        private Long leaseId;
        private String image;
        private String worktreeHostPath;
        private Map<String, String> env;
        private List<Map<String, Object>> portPublishes;
        private Map<String, String> cacheMounts;
        private Map<String, String> labels;
    }
}
