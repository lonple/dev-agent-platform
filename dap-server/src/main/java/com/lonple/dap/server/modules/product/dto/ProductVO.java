package com.lonple.dap.server.modules.product.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProductVO {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Long managerUserId;
    private String managerUsername;
    private Long wikiNodeId;
    private String wikiPath;
    private List<ProductCodeRepoVO> codeRepos = new ArrayList<>();
    private int versionCount;
    private List<ProductVersionVO> versions = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
