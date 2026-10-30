package com.lonple.dap.server.modules.tnworkspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_workspace_node_revision")
public class SysWorkspaceNodeRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long workspaceId;
    private Long nodeId;
    private Integer versionNo;
    private String content;
    private Long sessionId;
    private Long createdBy;
    private LocalDateTime createdAt;
}
