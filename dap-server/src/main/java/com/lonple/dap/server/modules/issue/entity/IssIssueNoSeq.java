package com.lonple.dap.server.modules.issue.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("iss_issue_no_seq")
public class IssIssueNoSeq {

    private Long tenantId;
    private Long productId;
    private Integer lastSeq;
}
