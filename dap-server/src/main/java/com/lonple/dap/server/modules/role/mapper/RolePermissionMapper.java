package com.lonple.dap.server.modules.role.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper {

    @Select("""
            SELECT p.code FROM sys_permission p
            INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
            """)
    List<String> selectCodesByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Insert("""
            INSERT INTO sys_role_permission(role_id, permission_id)
            SELECT #{roleId}, id FROM sys_permission WHERE code = #{code}
            """)
    int insertByCode(@Param("roleId") Long roleId, @Param("code") String code);
}
