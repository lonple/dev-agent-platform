package com.lonple.dap.server.modules.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_prompt")
public class SysPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    /** 绑定岗位 */
    private Long positionId;
    /** SessionTaskType.name */
    private String taskTypeCode;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
