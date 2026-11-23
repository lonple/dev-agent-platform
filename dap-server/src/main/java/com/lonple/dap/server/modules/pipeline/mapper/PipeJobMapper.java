package com.lonple.dap.server.modules.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.pipeline.entity.PipeJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PipeJobMapper extends BaseMapper<PipeJob> {
}
