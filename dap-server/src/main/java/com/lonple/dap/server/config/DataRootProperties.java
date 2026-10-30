package com.lonple.dap.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.data")
public class DataRootProperties {

    /** 相对项目根或绝对路径；与 dap-server/dap-front 同级的 data 目录 */
    private String root = "../data";
}
