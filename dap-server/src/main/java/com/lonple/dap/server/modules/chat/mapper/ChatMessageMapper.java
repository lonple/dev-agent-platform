package com.lonple.dap.server.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.chat.entity.WbChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<WbChatMessage> {
}
