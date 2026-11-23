package com.lonple.dap.server.modules.sandbox;

import com.lonple.dap.server.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SandboxManagerClient {

    private final SandboxProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> attach(
            Long leaseId,
            String nodeCode,
            String image,
            String worktreeHostPath,
            Map<String, String> env,
            List<Map<String, Object>> portPublishes,
            Map<String, String> cacheMounts) {
        Map<String, Object> body = new HashMap<>();
        body.put("nodeCode", nodeCode);
        body.put("image", image);
        body.put("worktreeHostPath", worktreeHostPath);
        body.put("env", env == null ? Map.of() : env);
        body.put("portPublishes", portPublishes == null ? List.of() : portPublishes);
        body.put("cacheMounts", cacheMounts == null ? Map.of() : cacheMounts);
        return post("/api/v1/leases/" + leaseId + "/attach", body);
    }

    public Map<String, Object> detach(Long leaseId) {
        return post("/api/v1/leases/" + leaseId + "/detach", Map.of());
    }

    public Map<String, Object> code(Long leaseId, String op, Object body) {
        return post("/api/v1/leases/" + leaseId + "/code/" + op, body == null ? Map.of() : body);
    }

    private Map<String, Object> post(String path, Object body) {
        String url = props.getManagerBaseUrl().replaceAll("/$", "") + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Sandbox-Token", props.getSharedToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody() == null ? Map.of() : resp.getBody();
        } catch (RestClientResponseException e) {
            throw new BizException(502, "sandbox-manager 调用失败: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BizException(502, "sandbox-manager 不可达: " + e.getMessage());
        }
    }
}
