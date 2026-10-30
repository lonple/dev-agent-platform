package com.lonple.dap.server.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<WbChatSession> {
}
