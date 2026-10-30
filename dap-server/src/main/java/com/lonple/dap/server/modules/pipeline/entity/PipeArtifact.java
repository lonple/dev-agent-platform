package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_artifact")
public class PipeArtifact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requirementId;
    private String kind;
    private Integer version;
    private Long wikiNodeId;
    private Long stepInstanceId;
    private String title;
    private Boolean isCurrent;
    private LocalDateTime createdAt;
}
