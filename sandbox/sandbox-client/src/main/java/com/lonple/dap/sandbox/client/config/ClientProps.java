package com.lonple.dap.sandbox.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class ClientProps {
    private String sharedToken = "dev-sandbox-token";
    private String nodeCode = "local-dev";
    private String managerBaseUrl = "http://127.0.0.1:9210";
    private String dockerCommand = "docker";
    private String image = "dap-sandbox-webdev:dev";
    private int portRangeStart = 9400;
    private int portRangeEnd = 9499;
    /** code-tools 在容器内监听端口（与业务 8080 分开） */
    private int codeToolsContainerPort = 9100;
    private int readyTimeoutSec = 90;
    private String m2CacheVolume = "dap-m2-cache";
    private String npmCacheVolume = "dap-npm-cache";
}
