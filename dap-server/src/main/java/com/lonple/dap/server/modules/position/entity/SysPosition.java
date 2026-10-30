package com.lonple.dap.server.modules.position.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_position")
public class SysPosition {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** PM / ARCH / DEV / QA 等，供任务类型默认岗位匹配 */
    private String code;
    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
