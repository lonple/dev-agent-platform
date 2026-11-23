package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pipe_confirm_round")
public class PipeConfirmRound {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long unitInstanceId;
    private Integer roundNo;
    private String phase;
    private String findingsJson;
    private String openQuestionsJson;
    private String humanAnswersJson;
    private Long wikiNodeId;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
}
