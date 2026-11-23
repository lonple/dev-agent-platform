package com.lonple.dap.codetools.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProps {
    private String workspaceRoot = "/workspace";
    private String sharedToken = "dev-sandbox-token";
    private int actionTimeoutSec = 120;
    /** ripgrep 可执行文件，默认 PATH 中的 rg */
    private String ripgrepExecutable = "rg";
    private int ripgrepTimeoutSec = 15;
    private int ripgrepMaxHits = 40;
}
