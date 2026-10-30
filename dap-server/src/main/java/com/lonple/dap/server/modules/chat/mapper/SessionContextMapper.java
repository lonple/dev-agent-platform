package com.lonple.dap.server.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.chat.entity.WbSessionContext;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionContextMapper extends BaseMapper<WbSessionContext> {
}
