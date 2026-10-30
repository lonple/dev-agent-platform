package com.lonple.dap.server.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.jwt")
public class JwtProperties {

    private String secret;
    private long expireHours = 24;
}
