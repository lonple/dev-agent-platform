package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_node_instance")
public class PipeNodeInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private String nodeCode;
    private String nodeName;
    private String kind;
    private String status;
    private Boolean optionalFlag;
    private Boolean enabledFlag;
    private Integer sortOrder;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
