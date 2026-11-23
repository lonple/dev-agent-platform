package com.lonple.dap.server.modules.sandbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sb_node")
public class SbNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String nodeCode;
    private String displayName;
    private String clientBaseUrl;
    private String status;
    private LocalDateTime lastHealthAt;
    private Boolean dockerOk;
    private String labelsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
