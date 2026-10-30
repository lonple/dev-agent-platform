package com.lonple.dap.server.modules.position.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkillVO {

    private Long id;
    private Long positionId;
    private String name;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
