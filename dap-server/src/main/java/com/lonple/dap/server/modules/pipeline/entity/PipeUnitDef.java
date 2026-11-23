package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pipe_unit_def")
public class PipeUnitDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long nodeDefId;
    private String code;
    private String name;
    private String positionCode;
    private String taskTypeCode;
    private String roundTier;
    private Integer maxRound;
    private String repoKind;
    private String outputKind;
    private Integer sortOrder;
    private String extJson;
}
