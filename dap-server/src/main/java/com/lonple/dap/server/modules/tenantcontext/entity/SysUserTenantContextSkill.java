package com.lonple.dap.server.modules.tenantcontext.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user_tenant_context_skill")
public class SysUserTenantContextSkill {

    private Long contextId;
    private Long skillId;
}
