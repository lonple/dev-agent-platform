package com.lonple.dap.server.modules.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class LoginResponse {

    private String token;
    private UserInfo user;
    private Set<String> permissions;
    private Long currentTenantId;
    private String currentTenantName;

    @Data
    @Builder
    public static class UserInfo {
        private Long id;
        private String username;
        private String description;
        private Long roleId;
        private String roleName;
        private boolean builtin;
    }
}
