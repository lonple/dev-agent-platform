package com.lonple.dap.server.modules.sandbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sb_port_allocation")
public class SbPortAllocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long leaseId;
    private Long nodeId;
    private String serviceKey;
    private Integer hostPort;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
