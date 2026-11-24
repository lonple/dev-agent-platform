package com.lonple.dap.server.modules.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProductSaveRequest {

    @NotBlank
    @Size(max = 64)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 500)
    private String description;

    private Long managerUserId;

    /** 已有 Wiki 目录节点；与 wikiCreate 二选一 */
    private Long wikiNodeId;

    /** 在父节点下新建目录作为产品 Wiki 根；parentId 空=Wiki 根 */
    @Valid
    private WikiCreateSpec wikiCreate;

    @Valid
    private List<CodeRepoSpec> codeRepos = new ArrayList<>();

    @Data
    public static class WikiCreateSpec {
        private Long parentId;
        @NotBlank
        @Size(max = 200)
        private String displayName;
    }

    @Data
    public static class CodeRepoSpec {
        /** BIZ / TEST / OTHER；未传或空值兼容为 BIZ。 */
        private String repoKind;
        @NotBlank
        @Size(max = 1000)
        private String localPath;
        @Size(max = 128)
        private String label;
    }
}
