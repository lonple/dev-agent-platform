package com.lonple.dap.server.modules.requirement.dto;

import lombok.Data;

@Data
public class RequirementSubmitRequest {

    /** 审批人；空则默认产品经理 */
    private Long approverId;
}
