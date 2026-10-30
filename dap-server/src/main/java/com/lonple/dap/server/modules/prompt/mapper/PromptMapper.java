package com.lonple.dap.server.modules.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lonple.dap.server.modules.prompt.entity.SysPrompt;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptMapper extends BaseMapper<SysPrompt> {
}
