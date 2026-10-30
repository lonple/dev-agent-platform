package com.lonple.dap.server.modules.tenantcontext.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.tenantcontext.entity.SysUserTenantContext;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserTenantContextMapper extends BaseMapper<SysUserTenantContext> {
}
