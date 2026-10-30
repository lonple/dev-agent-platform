package com.lonple.dap.server.modules.wiki.dto;

import lombok.Data;

@Data
public class WikiFileContentVO {
    private Long id;
    private String displayName;
    private String content;
}
