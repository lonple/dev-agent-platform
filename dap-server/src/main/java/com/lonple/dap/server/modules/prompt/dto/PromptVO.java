package com.lonple.dap.server.modules.prompt.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PromptVO {
    private Long id;
    private String code;
    private String name;
    private Long positionId;
    private String positionName;
    private String positionCode;
    private String taskTypeCode;
    private String taskTypeName;
    private String taskTypeKind;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
