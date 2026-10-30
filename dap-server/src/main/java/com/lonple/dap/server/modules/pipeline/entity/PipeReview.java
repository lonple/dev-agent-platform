package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_review")
public class PipeReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stepInstanceId;
    private Long requirementId;
    private String status;
    private Long summaryWikiNodeId;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
}
