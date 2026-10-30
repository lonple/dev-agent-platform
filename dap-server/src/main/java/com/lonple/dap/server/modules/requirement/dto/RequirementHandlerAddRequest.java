package com.lonple.dap.server.modules.requirement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RequirementHandlerAddRequest {

    /**
     * 批量：多组「岗位 + 多人」。
     * 若为空则回退单组 positionId + userIds。
     */
    @Valid
    private List<Item> items = new ArrayList<>();

    /** 单组兼容 */
    private Long positionId;
    private Long userId;
    private List<Long> userIds = new ArrayList<>();

    @Data
    public static class Item {
        @NotNull
        private Long positionId;
        private List<Long> userIds = new ArrayList<>();
    }
}
