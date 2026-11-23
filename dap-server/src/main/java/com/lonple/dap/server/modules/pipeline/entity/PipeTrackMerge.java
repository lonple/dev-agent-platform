package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_track_merge")
public class PipeTrackMerge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private String trackCode;
    private String status;
    private String llmMergeJson;
    private String itemsJson;
    private Long mergedWikiNodeId;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
}
