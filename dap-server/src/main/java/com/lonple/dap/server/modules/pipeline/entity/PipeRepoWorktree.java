package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_repo_worktree")
public class PipeRepoWorktree {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private Long requirementId;
    private Long issueId;
    private Long productCodeRepoId;
    private String repoKind;
    private String branchName;
    private String worktreePath;
    private String baseLocalPath;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
