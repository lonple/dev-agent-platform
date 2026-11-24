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

    /** 指定固定索引或别名；配置后读写均使用此名称，不按月份切分。 */
    private String stepIndexName;

    /** detail 文本最大字符（截断） */
    /** llm call 的 prompt 快照会较大；默认 80k，可用配置覆盖 */
    private int stepDetailMaxChars = 80000;
}
