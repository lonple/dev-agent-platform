package com.lonple.dap.server.modules.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pipe_node_def")
public class PipeNodeDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private String code;
    private String name;
    private String kind;
    private Boolean optionalFlag;
    private String parallelGroup;
    private String joinGroup;
    private Integer sortOrder;
    private String extJson;
}
