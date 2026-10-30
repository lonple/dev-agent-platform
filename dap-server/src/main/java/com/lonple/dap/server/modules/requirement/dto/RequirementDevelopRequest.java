package com.lonple.dap.server.modules.requirement.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RequirementDevelopRequest {

    /** 一并加入处理人列表的用户（可多选，可空） */
    private List<Long> userIds = new ArrayList<>();
}
