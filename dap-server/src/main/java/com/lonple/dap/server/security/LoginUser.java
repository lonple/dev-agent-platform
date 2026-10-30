package com.lonple.dap.server.security;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class LoginUser {

    private Long userId;
    private String username;
    private boolean builtin;
    private Long roleId;
    private String roleName;
    private Set<String> permissions = new HashSet<>();
    private Long currentTenantId;
}
