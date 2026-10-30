package com.lonple.dap.server.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wb_session_code_repo")
public class WbSessionCodeRepo {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String localPath;
    private Integer sortNo;
    private LocalDateTime createdAt;
}
