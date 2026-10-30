package com.lonple.dap.server.modules.requirement.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RequirementNoSeqMapper {

    @Insert("""
            INSERT INTO req_requirement_no_seq (tenant_id, product_id, year_no, last_seq)
            VALUES (#{tenantId}, #{productId}, #{yearNo}, -1)
            ON CONFLICT (tenant_id, product_id, year_no) DO NOTHING
            """)
    int ensureRow(
            @Param("tenantId") Long tenantId,
            @Param("productId") Long productId,
            @Param("yearNo") int yearNo);

    @Select("""
            SELECT last_seq FROM req_requirement_no_seq
            WHERE tenant_id = #{tenantId} AND product_id = #{productId} AND year_no = #{yearNo}
            FOR UPDATE
            """)
    Integer selectLastSeqForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("productId") Long productId,
            @Param("yearNo") int yearNo);

    @Update("""
            UPDATE req_requirement_no_seq
            SET last_seq = #{lastSeq}
            WHERE tenant_id = #{tenantId} AND product_id = #{productId} AND year_no = #{yearNo}
            """)
    int updateLastSeq(
            @Param("tenantId") Long tenantId,
            @Param("productId") Long productId,
            @Param("yearNo") int yearNo,
            @Param("lastSeq") int lastSeq);
}
