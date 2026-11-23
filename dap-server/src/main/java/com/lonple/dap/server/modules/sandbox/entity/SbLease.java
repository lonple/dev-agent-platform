package com.lonple.dap.server.modules.sandbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sb_lease")
public class SbLease {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private Long resourceId;
    private Long profileId;
    private Integer profileVersion;
    private String integrationEnvCode;
    private String reqNo;
    private Long sessionId;
    private Long jobId;
    private String worktreeHostPath;
    private String purpose;
    private String status;
    private String runtimeInstanceJson;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
}
