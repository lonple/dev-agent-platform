package com.lonple.dap.server.modules.review.task;

import com.lonple.dap.server.modules.review.git.RepoType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TaskConfig {
    private String id;
    private String name;
    private String productName;
    private TaskStatus status = TaskStatus.DRAFT;
    private RepoType repoType = RepoType.LOCAL;
    private String localPath;
    private String remoteUrl;
    private String codeKey;
    private String workTreePath;
    private String baseBranch;
    private String reviewBranch;
    private List<String> availableBranches = new ArrayList<>();
    private boolean hasCredential;
    private String createdAt;
    private String updatedAt;
    private String lastRunAt;
}
