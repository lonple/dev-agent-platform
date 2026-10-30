package com.lonple.dap.server.modules.position.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PositionVO {

    private Long id;
    private String code;
    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SkillVO> skills;
}
