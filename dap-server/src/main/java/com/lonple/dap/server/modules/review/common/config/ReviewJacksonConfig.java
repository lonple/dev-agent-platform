package com.lonple.dap.server.modules.review.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评审引擎沿用 Jackson 2。平台其余代码使用 Boot 4 的 tools.jackson，两者按类型分开注入。
 */
@Configuration
public class ReviewJacksonConfig {

    @Bean
    public ObjectMapper reviewObjectMapper() {
        return new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
