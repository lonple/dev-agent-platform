package com.lonple.dap.server.modules.tnworkspace.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TenantWorkspaceVO {
    private Long id;
    private String spaceType;
    private String spaceTypeLabel;
    private String name;
    private Long ownerUserId;
    private String ownerUsername;
    private String workDir;
    private Integer status;
    private String statusLabel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
