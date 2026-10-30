package com.lonple.dap.server.modules.tnworkspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_workspace_node")
public class SysWorkspaceNode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long workspaceId;
    private Long parentId;
    /** DIR / FILE */
    private String nodeType;
    /** 页面展示名 */
    private String displayName;
    /** 磁盘实际名（目录可为业务名；文件为随机字符） */
    private String storageName;
    private Long requirementId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
