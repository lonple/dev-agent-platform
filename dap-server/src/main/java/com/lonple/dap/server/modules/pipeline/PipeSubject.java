package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;

/**
 * 流水线主体：需求或问题单（二选一）。
 */
public record PipeSubject(
        SysProduct product,
        ReqRequirement requirement,
        IssIssue issue
) {
    public boolean isIssue() {
        return issue != null;
    }

    public boolean isRequirement() {
        return requirement != null;
    }

    public Long productId() {
        return product.getId();
    }

    public String refNo() {
        if (issue != null) {
            return issue.getIssueNo();
        }
        if (requirement != null) {
            return requirement.getReqNo();
        }
        return null;
    }

    public String displayName() {
        if (issue != null) {
            return issue.getIssueNo();
        }
        if (requirement != null) {
            return requirement.getName();
        }
        return null;
    }

    public Long requirementIdOrNull() {
        return requirement == null ? null : requirement.getId();
    }

    public Long issueIdOrNull() {
        return issue == null ? null : issue.getId();
    }

    public Long linkedRequirementIdOrNull() {
        if (requirement != null) {
            return requirement.getId();
        }
        return issue == null ? null : issue.getRequirementId();
    }
}
