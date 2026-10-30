package com.lonple.dap.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.search.ripgrep")
public class RipgrepProperties {

    /** 是否优先使用 ripgrep；不可用时自动回退行扫描 */
    private boolean enabled = true;

    /** 可执行文件名或绝对路径，默认依赖 PATH 中的 rg */
    private String executable = "rg";

    private int timeoutMs = 15000;

    /** 单次搜索最多返回命中条数 */
    private int maxHits = 40;

    /** 传给 rg 的单文件最大命中数 */
    private int maxCountPerFile = 5;
}
