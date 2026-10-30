package com.lonple.dap.server.modules.tenantcontext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user_tenant_context")
public class SysUserTenantContext {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long positionId;
    private String customSkillText;
    private LocalDateTime updatedAt;
}
