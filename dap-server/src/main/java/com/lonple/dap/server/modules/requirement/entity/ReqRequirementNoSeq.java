package com.lonple.dap.server.modules.requirement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("req_requirement_no_seq")
public class ReqRequirementNoSeq {

    private Long tenantId;
    private Long productId;
    private Integer yearNo;
    private Integer lastSeq;
}
