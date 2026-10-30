package com.lonple.dap.server.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wb_chat_session")
public class WbChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long workspaceId;
    /** NORMAL / TASK */
    private String sessionType;
    private String title;
    private Long requirementId;
    /** SessionTaskType.name，仅 TASK */
    private String taskTypeCode;
    /** 个人空间输出目录（手动任务）；流水线任务请用 outputWikiDirNodeId */
    private Long outputDirNodeId;
    /** 流水线任务输出目录：Wiki DIR 节点（可空） */
    private Long outputWikiDirNodeId;
    /** 输出参考模板：Wiki「模板」目录下的文件节点（可空） */
    private Long outputTemplateWikiNodeId;
    /** 规范：Wiki「规范」下的 DIR 或 FILE 节点（可空） */
    private Long standardsWikiNodeId;
    /** 关联流水线步骤（可空；用于回写 token） */
    private Long pipeStepInstanceId;
    private Long positionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
