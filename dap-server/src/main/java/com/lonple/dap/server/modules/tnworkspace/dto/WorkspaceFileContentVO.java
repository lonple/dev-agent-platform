package com.lonple.dap.server.modules.tnworkspace.dto;

import lombok.Data;

@Data
public class WorkspaceFileContentVO {
    private Long id;
    private String displayName;
    private String content;
    private Long requirementId;
}
