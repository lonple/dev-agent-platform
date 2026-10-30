package com.lonple.dap.server.modules.requirement.dto;

import lombok.Data;

@Data
public class RequirementHandlerVO {
    private Long id;
    private Long userId;
    private String username;
    /** 岗位 ID；投入研发默认负责人可为空 */
    private Long positionId;
    /** 岗位名称；无岗位时为「负责人」 */
    private String roleLabel;
    private boolean applicant;
    private boolean currentHandler;
}
