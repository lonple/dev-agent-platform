package com.lonple.dap.sandbox.manager.client;

import com.lonple.dap.sandbox.manager.config.ManagerProps;
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

import java.util.Map;

@Component
@RequiredArgsConstructor
public class SandboxClientHttp {

    private final ManagerProps props;
    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> getHealth(String clientBaseUrl) {
        return exchange(clientBaseUrl + "/api/v1/health", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> attach(String clientBaseUrl, Map<String, Object> body) {
        return exchange(clientBaseUrl + "/api/v1/sandboxes/attach", HttpMethod.POST, body,
                new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> detach(String clientBaseUrl, Long leaseId) {
        return exchange(clientBaseUrl + "/api/v1/sandboxes/" + leaseId + "/detach", HttpMethod.POST, Map.of(),
                new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> code(String clientBaseUrl, Long leaseId, String op, Object body) {
        return exchange(clientBaseUrl + "/api/v1/sandboxes/" + leaseId + "/code/" + op, HttpMethod.POST, body,
                new ParameterizedTypeReference<>() {});
    }

    private <T> T exchange(
            String url, HttpMethod method, Object body, ParameterizedTypeReference<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Sandbox-Token", props.getSharedToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<T> resp = restTemplate.exchange(url, method, new HttpEntity<>(body, headers), type);
            return resp.getBody();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("client call failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }
}
