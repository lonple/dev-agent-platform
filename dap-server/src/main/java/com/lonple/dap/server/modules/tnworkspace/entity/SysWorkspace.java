package com.lonple.dap.server.modules.tnworkspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_workspace")
public class SysWorkspace {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** SHARED / PERSONAL */
    private String spaceType;
    /** 显示名，可中文 */
    private String name;
    /**
     * 个人空间所有者；共享空间存创建人（不在 UI 展示）。
     * 磁盘路径个人：{tenant}/{username}/{workDir}；共享：{tenant}/share/{workDir}
     */
    private Long ownerUserId;
    /** 磁盘目录名（单段） */
    private String workDir;
    /** 1 启用 0 禁用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
