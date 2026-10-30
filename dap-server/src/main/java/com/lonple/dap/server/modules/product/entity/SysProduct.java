package com.lonple.dap.server.modules.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_product")
public class SysProduct {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String description;
    private Long managerUserId;
    /** 产品 Wiki 上下文根目录节点 */
    private Long wikiNodeId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
