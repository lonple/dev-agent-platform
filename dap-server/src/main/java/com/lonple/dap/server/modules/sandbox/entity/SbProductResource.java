package com.lonple.dap.server.modules.sandbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sb_product_resource")
public class SbProductResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private String resourceCode;
    private Long nodeId;
    private String image;
    private Integer maxSlots;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
