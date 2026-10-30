package com.lonple.dap.server.modules.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_product_code_repo")
public class SysProductCodeRepo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String localPath;
    private String label;
    private Integer sortNo;
    private LocalDateTime createdAt;
}
