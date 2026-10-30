package com.lonple.dap.server.modules.tenantcontext.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.tenantcontext.entity.SysUserTenantContextSkill;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserTenantContextSkillMapper extends BaseMapper<SysUserTenantContextSkill> {

    @Select("SELECT context_id AS contextId, skill_id AS skillId FROM sys_user_tenant_context_skill WHERE context_id = #{contextId}")
    List<SysUserTenantContextSkill> listByContextId(@Param("contextId") Long contextId);

    @Insert("INSERT INTO sys_user_tenant_context_skill(context_id, skill_id) VALUES(#{contextId}, #{skillId})")
    int insertLink(@Param("contextId") Long contextId, @Param("skillId") Long skillId);

    @Delete("DELETE FROM sys_user_tenant_context_skill WHERE context_id = #{contextId}")
    int deleteByContextId(@Param("contextId") Long contextId);
}
