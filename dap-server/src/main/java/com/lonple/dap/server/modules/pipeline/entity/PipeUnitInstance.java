package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_unit_instance")
public class PipeUnitInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long nodeInstanceId;
    private Long instanceId;
    private String unitCode;
    private String unitName;
    private String positionCode;
    private String taskTypeCode;
    private String status;
    private String roundTier;
    private Integer maxRound;
    private Integer confirmRound;
    private String repoKind;
    private Long sessionId;
    /** 正式产出在个人空间的文件节点（完成输出后） */
    private Long outputWorkspaceNodeId;
    /** 正式产出发布到 Wiki 后的节点（手动发布后） */
    private Long outputWikiNodeId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
