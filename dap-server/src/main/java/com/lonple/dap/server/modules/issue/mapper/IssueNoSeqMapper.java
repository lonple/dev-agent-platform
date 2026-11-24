package com.lonple.dap.server.modules.issue.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IssueNoSeqMapper {

    /**
     * 确保 (tenant_id, product_id) 的序列行存在。
     * 使用 INSERT ... SELECT ... WHERE NOT EXISTS 而不是 ON CONFLICT：不依赖序列表上是否
     * 存在 (tenant_id, product_id) 唯一约束/唯一索引，避免环境里约束缺失时
     * POST /api/issues 直接抛 SQL 错误返回 500；同时保证重复执行幂等。
     */
    @Insert("""
            INSERT INTO iss_issue_no_seq (tenant_id, product_id, last_seq)
            SELECT #{tenantId}, #{productId}, -1
            WHERE NOT EXISTS (
                SELECT 1 FROM iss_issue_no_seq
                WHERE tenant_id = #{tenantId} AND product_id = #{productId}
            )
            """)
    int ensureRow(@Param("tenantId") Long tenantId, @Param("productId") Long productId);

    @Select("""
            SELECT last_seq FROM iss_issue_no_seq
            WHERE tenant_id = #{tenantId} AND product_id = #{productId}
            FOR UPDATE
            """)
    Integer selectLastSeqForUpdate(@Param("tenantId") Long tenantId, @Param("productId") Long productId);

    @Update("""
            UPDATE iss_issue_no_seq
            SET last_seq = #{lastSeq}
            WHERE tenant_id = #{tenantId} AND product_id = #{productId}
            """)
    int updateLastSeq(
            @Param("tenantId") Long tenantId,
            @Param("productId") Long productId,
            @Param("lastSeq") int lastSeq);

    /**
     * 序列与存量数据对齐：把 last_seq 抬到不低于该 (租户, 产品) 已存在的问题数，
     * 避免历史数据/重复执行导致序列落后、再次生成已存在的 issue_no。
     */
    @Update("""
            UPDATE iss_issue_no_seq s
            SET last_seq = GREATEST(s.last_seq, d.cnt - 1)
            FROM (
                SELECT tenant_id, product_id, COUNT(*)::int AS cnt
                FROM iss_issue
                WHERE tenant_id = #{tenantId} AND product_id = #{productId}
                GROUP BY tenant_id, product_id
            ) d
            WHERE s.tenant_id = d.tenant_id AND s.product_id = d.product_id
              AND s.tenant_id = #{tenantId} AND s.product_id = #{productId}
            """)
    int syncLastSeqWithData(@Param("tenantId") Long tenantId, @Param("productId") Long productId);
}
