package com.lonple.dap.server.modules.requirement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirementHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RequirementHandlerMapper extends BaseMapper<ReqRequirementHandler> {

    @Insert("""
            INSERT INTO req_requirement_handler (tenant_id, requirement_id, user_id, position_id, created_at)
            VALUES (#{tenantId}, #{requirementId}, #{userId}, #{positionId}, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """)
    int insertIgnore(
            @Param("tenantId") Long tenantId,
            @Param("requirementId") Long requirementId,
            @Param("userId") Long userId,
            @Param("positionId") Long positionId);

    @Select("""
            SELECT COUNT(1) > 0 FROM req_requirement_handler
            WHERE requirement_id = #{requirementId} AND user_id = #{userId}
            """)
    boolean exists(@Param("requirementId") Long requirementId, @Param("userId") Long userId);

    @Select("""
            SELECT requirement_id FROM req_requirement_handler
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    List<Long> selectRequirementIdsByUser(
            @Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM req_requirement_handler
            WHERE id = #{id} AND requirement_id = #{requirementId}
            """)
    int deleteByIdAndRequirement(@Param("requirementId") Long requirementId, @Param("id") Long id);

    @Delete("""
            DELETE FROM req_requirement_handler
            WHERE requirement_id = #{requirementId} AND user_id = #{userId}
            """)
    int deleteOne(@Param("requirementId") Long requirementId, @Param("userId") Long userId);

    @Delete("DELETE FROM req_requirement_handler WHERE requirement_id = #{requirementId}")
    int deleteByRequirement(@Param("requirementId") Long requirementId);
}
