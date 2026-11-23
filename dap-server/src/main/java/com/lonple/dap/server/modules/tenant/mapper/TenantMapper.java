package com.lonple.dap.server.modules.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper extends BaseMapper<SysTenant> {

    @Delete("DELETE FROM req_requirement WHERE tenant_id = #{tenantId}")
    int deleteRequirementsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM req_requirement_no_seq WHERE tenant_id = #{tenantId}")
    int deleteRequirementNoSeqByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM iss_issue WHERE tenant_id = #{tenantId}")
    int deleteIssuesByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM iss_issue_no_seq WHERE tenant_id = #{tenantId}")
    int deleteIssueNoSeqByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_product WHERE tenant_id = #{tenantId}")
    int deleteProductsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_workspace_node WHERE tenant_id = #{tenantId}")
    int deleteWorkspaceNodesByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_workspace WHERE tenant_id = #{tenantId}")
    int deleteWorkspacesByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM wb_chat_session WHERE tenant_id = #{tenantId}")
    int deleteChatSessionsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_user_tenant_context WHERE tenant_id = #{tenantId}")
    int deleteUserTenantContextsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("""
            DELETE FROM sys_position_skill WHERE position_id IN (
                SELECT id FROM sys_position WHERE tenant_id = #{tenantId}
            )
            """)
    int deletePositionSkillsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_position WHERE tenant_id = #{tenantId}")
    int deletePositionsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_prompt WHERE tenant_id = #{tenantId}")
    int deletePromptsByTenantId(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM sys_tenant_user WHERE tenant_id = #{tenantId}")
    int deleteTenantUsersByTenantId(@Param("tenantId") Long tenantId);
}
