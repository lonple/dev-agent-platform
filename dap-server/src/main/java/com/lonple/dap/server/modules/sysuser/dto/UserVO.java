package com.lonple.dap.server.modules.sysuser.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserVO {

    private Long id;
    private String username;
    private String description;
    /** 平台角色 */
    private Long roleId;
    private String roleName;
    private boolean builtin;
    /** 当前租户角色：ADMIN / OPERATOR */
    private String tenantRole;
    private String tenantRoleLabel;
    /** 是否租户管理员（tenantRole=ADMIN） */
    private Boolean tenantAdmin;
}
