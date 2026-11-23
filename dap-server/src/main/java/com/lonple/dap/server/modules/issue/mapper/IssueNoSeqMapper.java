package com.lonple.dap.server.modules.issue.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IssueNoSeqMapper {

    @Insert("""
            INSERT INTO iss_issue_no_seq (tenant_id, product_id, last_seq)
            VALUES (#{tenantId}, #{productId}, -1)
            ON CONFLICT (tenant_id, product_id) DO NOTHING
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
}
