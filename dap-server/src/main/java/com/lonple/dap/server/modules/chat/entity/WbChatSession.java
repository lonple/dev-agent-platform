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
    /** 任务会话关联问题单（问题流水） */
    private Long issueId;
    /** SessionTaskType.name，仅 TASK */
    private String taskTypeCode;
    /** 个人空间输出目录 */
    private Long outputDirNodeId;
    /** 输出参考模板：Wiki「模板」目录下的文件节点（可空） */
    private Long outputTemplateWikiNodeId;
    /** 规范：Wiki「规范」下的 DIR 或 FILE 节点（可空） */
    private Long standardsWikiNodeId;
    private Long positionId;
    /** 流水线 Agent 单元实例 */
    private Long pipeUnitInstanceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
