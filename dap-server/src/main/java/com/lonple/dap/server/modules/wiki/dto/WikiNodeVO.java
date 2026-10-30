package com.lonple.dap.server.modules.wiki.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WikiNodeVO {
    private Long id;
    private Long parentId;
    private String nodeType;
    private String displayName;
    private List<WikiNodeVO> children = new ArrayList<>();
}
