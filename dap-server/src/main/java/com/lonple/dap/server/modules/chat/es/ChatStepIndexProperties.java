package com.lonple.dap.server.modules.chat.es;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.chat")
public class ChatStepIndexProperties {

    /** 索引环境前缀，如 dev → dev-dap-chat-step-yyyy.MM */
    private String stepIndexEnv = "dev";

    /** detail 文本最大字符（截断） */
    private int stepDetailMaxChars = 20000;
}
