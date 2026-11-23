package com.lonple.dap.server.modules.review.credential;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CredentialStore {
    private final ConcurrentHashMap<String, Cred> map = new ConcurrentHashMap<>();

    public void put(String taskId, String username, String password) {
        if (!StringUtils.hasText(username) && !StringUtils.hasText(password)) {
            return;
        }
        map.put(taskId, new Cred(
                username == null ? "" : username,
                password == null ? "" : password));
    }

    public Optional<Cred> get(String taskId) {
        return Optional.ofNullable(map.get(taskId));
    }

    public boolean has(String taskId) {
        return map.containsKey(taskId);
    }

    public void clear(String taskId) {
        map.remove(taskId);
    }

    public record Cred(String username, String password) {
    }
}
