package com.lonple.dap.server.modules.tenant.mapper;

import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TenantUserMapper {

    @Select("""
            SELECT COUNT(1) > 0 FROM sys_tenant_user tu
            INNER JOIN sys_tenant t ON t.id = tu.tenant_id
            WHERE tu.tenant_id = #{tenantId} AND tu.user_id = #{userId} AND t.status = 1
            """)
    boolean existsMembership(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
            SELECT tenant_role FROM sys_tenant_user
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    String selectTenantRole(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("SELECT COUNT(1) FROM sys_tenant_user WHERE tenant_id = #{tenantId}")
    long countByTenantId(@Param("tenantId") Long tenantId);

    @Select("SELECT COUNT(1) FROM sys_tenant_user WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_tenant_user WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_tenant_user WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    int deleteMember(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO sys_tenant_user(tenant_id, user_id, tenant_role)
            VALUES(#{tenantId}, #{userId}, #{tenantRole})
            ON CONFLICT (tenant_id, user_id) DO UPDATE SET tenant_role = EXCLUDED.tenant_role
            """)
    int upsertMember(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("tenantRole") String tenantRole);

    @Update("UPDATE sys_tenant_user SET tenant_role = 'OPERATOR' WHERE tenant_id = #{tenantId}")
    int resetAllToOperator(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT u.* FROM sys_user u
            INNER JOIN sys_tenant_user tu ON tu.user_id = u.id
            WHERE tu.tenant_id = #{tenantId}
            ORDER BY CASE WHEN tu.tenant_role = 'ADMIN' THEN 0 ELSE 1 END, u.id
            """)
    List<SysUser> selectUsersByTenantId(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT u.* FROM sys_user u
            INNER JOIN sys_tenant_user tu ON tu.user_id = u.id
            WHERE tu.tenant_id = #{tenantId} AND tu.tenant_role = 'ADMIN'
            ORDER BY u.id
            """)
    List<SysUser> selectAdminsByTenantId(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT t.* FROM sys_tenant t
            INNER JOIN sys_tenant_user tu ON tu.tenant_id = t.id
            WHERE tu.user_id = #{userId} AND t.status = 1
            ORDER BY t.id
            """)
    List<SysTenant> selectEnabledTenantsByUserId(@Param("userId") Long userId);
}
