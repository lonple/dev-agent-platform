package com.lonple.dap.server.modules.review.task.dto;

import com.lonple.dap.server.modules.review.git.RepoType;
import lombok.Data;

@Data
public class UpdateConfigRequest {
    private String name;
    private String productName;
    private RepoType repoType;
    private String localPath;
    private String remoteUrl;
    private String baseBranch;
    private String reviewBranch;
}
