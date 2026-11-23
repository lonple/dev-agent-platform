package com.lonple.dap.server.modules.sandbox;

/** 产品无关的原始运行方案。不预填业务端口、依赖地址或启动命令。 */
public final class RuntimeProfileDefaults {

    private RuntimeProfileDefaults() {
    }

    public static final String DEFAULT_CONFIG_JSON = """
            {
              "imageOverride": null,
              "sandbox": {
                "preferredResourceCodes": [],
                "leaseTtlHours": 8
              },
              "ports": {
                "pool": { "start": 18000, "end": 18999 },
                "services": []
              },
              "envTemplates": [],
              "startTemplates": [],
              "cacheMounts": { "m2": false, "npm": false },
              "envVars": []
            }
            """;
}
