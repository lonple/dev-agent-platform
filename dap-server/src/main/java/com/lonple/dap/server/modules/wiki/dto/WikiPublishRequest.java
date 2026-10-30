package com.lonple.dap.server.modules.wiki.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WikiPublishRequest {

    @NotNull
    private Long sourceWorkspaceId;

    @NotNull
    private Long sourceNodeId;

    /** Wiki 目标目录，null 表示根 */
    private Long targetParentId;
}
