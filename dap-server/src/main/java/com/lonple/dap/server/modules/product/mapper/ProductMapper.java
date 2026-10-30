package com.lonple.dap.server.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<SysProduct> {

    @Select("""
            SELECT u.* FROM sys_user u
            INNER JOIN sys_tenant_user tu ON tu.user_id = u.id
            WHERE tu.tenant_id = #{tenantId}
            ORDER BY u.id
            """)
    List<SysUser> selectProductManagers(@Param("tenantId") Long tenantId);
}
