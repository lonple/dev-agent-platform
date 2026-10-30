package com.lonple.dap.server.modules.product.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductVersionVO {
    private Long id;
    private Long productId;
    private String version;
    private String status;
    private String statusLabel;
    private LocalDateTime planReleaseAt;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
