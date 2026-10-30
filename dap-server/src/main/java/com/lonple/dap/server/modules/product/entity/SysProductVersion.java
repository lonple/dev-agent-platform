package com.lonple.dap.server.modules.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_product_version")
public class SysProductVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private String version;
    private String status;
    private LocalDateTime planReleaseAt;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
