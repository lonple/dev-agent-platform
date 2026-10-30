package com.lonple.dap.server.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wb_chat_message")
public class WbChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String role;
    private String thinking;
    private String content;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
