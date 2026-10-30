package com.lonple.dap.server.modules.product.dto;

import lombok.Data;

@Data
public class ProductCodeRepoVO {
    private Long id;
    private String localPath;
    private String label;
    private Integer sortNo;
}
