package com.lonple.dap.server.modules.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.audit.dto.TokenUsageAggRow;
import com.lonple.dap.server.modules.audit.entity.LlmTokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LlmTokenUsageMapper extends BaseMapper<LlmTokenUsage> {

    @Select("""
            SELECT user_id AS userId,
                   SUM(prompt_tokens) AS promptTokens,
                   SUM(completion_tokens) AS completionTokens,
                   SUM(total_tokens) AS totalTokens
            FROM llm_token_usage
            WHERE tenant_id = #{tenantId}
            GROUP BY user_id
            ORDER BY SUM(total_tokens) DESC
            """)
    List<TokenUsageAggRow> sumByUser(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT requirement_id AS requirementId,
                   user_id AS userId,
                   SUM(prompt_tokens) AS promptTokens,
                   SUM(completion_tokens) AS completionTokens,
                   SUM(total_tokens) AS totalTokens
            FROM llm_token_usage
            WHERE tenant_id = #{tenantId}
              AND requirement_id IS NOT NULL
            GROUP BY requirement_id, user_id
            ORDER BY SUM(total_tokens) DESC
            """)
    List<TokenUsageAggRow> sumByRequirementUser(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT issue_id AS issueId,
                   user_id AS userId,
                   SUM(prompt_tokens) AS promptTokens,
                   SUM(completion_tokens) AS completionTokens,
                   SUM(total_tokens) AS totalTokens
            FROM llm_token_usage
            WHERE tenant_id = #{tenantId}
              AND issue_id IS NOT NULL
            GROUP BY issue_id, user_id
            ORDER BY SUM(total_tokens) DESC
            """)
    List<TokenUsageAggRow> sumByIssueUser(@Param("tenantId") Long tenantId);
}
