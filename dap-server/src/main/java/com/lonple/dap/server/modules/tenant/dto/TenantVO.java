package com.lonple.dap.server.modules.tenant.dto;

import com.lonple.dap.server.modules.sysuser.dto.UserVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TenantVO {
    private Long id;
    private String code;
    private String name;
    private String description;
    private Integer status;
    private List<UserVO> admins = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
