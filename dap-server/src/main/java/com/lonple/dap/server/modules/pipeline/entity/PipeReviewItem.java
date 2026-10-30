package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_review_item")
public class PipeReviewItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reviewId;
    private Integer ordinal;
    private String content;
    private Boolean checked;
    private String source;
    private Long amendUserId;
    private LocalDateTime createdAt;
}
