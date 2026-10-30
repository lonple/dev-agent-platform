package com.lonple.dap.server.modules.requirement.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ApproverCandidatesVO {

    private Long productManagerId;
    private String productManagerUsername;
    /** 建议默认审批人（产品经理，且不是本人） */
    private Long defaultApproverId;
    private List<Candidate> candidates = new ArrayList<>();

    @Data
    public static class Candidate {
        private Long id;
        private String username;
        private boolean productManager;
    }
}
