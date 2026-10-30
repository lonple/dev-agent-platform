package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pipe_step_def")
public class PipeStepDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private String code;
    private String name;
    private String stage;
    private String executor;
    private String rolePackCode;
    private String taskTypeCode;
    private String templateWikiPath;
    private String inputArtifactKinds;
    private String outputArtifactKind;
    private String parallelGroup;
    private String joinGroup;
    private Integer maxRound;
    private Boolean autoAdvance;
    private String humanRole;
    private String assigneePolicy;
    private Integer assigneeQuorum;
    private String rejectPolicy;
    private String onReject;
    private Integer sortOrder;
}
