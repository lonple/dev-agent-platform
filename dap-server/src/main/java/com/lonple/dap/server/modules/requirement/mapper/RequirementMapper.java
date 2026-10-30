package com.lonple.dap.server.modules.requirement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequirementMapper extends BaseMapper<ReqRequirement> {
}
