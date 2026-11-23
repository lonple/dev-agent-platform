package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_job")
public class PipeJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private Long unitInstanceId;
    private String jobType;
    private String payloadJson;
    private String status;
    private Integer attempts;
    private Integer maxAttempts;
    private LocalDateTime nextRunAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
