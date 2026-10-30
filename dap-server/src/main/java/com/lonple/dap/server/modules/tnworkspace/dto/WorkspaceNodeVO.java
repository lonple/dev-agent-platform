package com.lonple.dap.server.modules.tnworkspace.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorkspaceNodeVO {
    private Long id;
    private Long parentId;
    private String nodeType;
    private String displayName;
    private Long requirementId;
    private List<WorkspaceNodeVO> children = new ArrayList<>();
}
