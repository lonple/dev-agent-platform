package com.lonple.dap.server.modules.requirement;

public enum RequirementFlowNodeType {
    SUBMIT("提交申请"),
    APPROVE("审批通过"),
    REJECT("驳回"),
    PLAN("提交计划"),
    DEVELOP("投入研发");

    private final String label;

    RequirementFlowNodeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
