package com.lonple.dap.server.modules.sandbox;

/** 产品默认 Profile 模板（webdev）。 */
public final class RuntimeProfileDefaults {

    private RuntimeProfileDefaults() {
    }

    public static final String DEFAULT_CONFIG_JSON = """
            {
              "imageOverride": null,
              "sandbox": {
                "preferredResourceCodes": [],
                "leaseTtlHours": 8,
                "releaseWorktreeOnClose": false,
                "autoCommitOnRelease": false
              },
              "database": {
                "schemaPattern": "it_{productCode}_{reqNoSafe}",
                "urlTemplate": "jdbc:postgresql://{dbHost}:{dbPort}/{dbName}?currentSchema={schema}",
                "dbHostRef": "integration.dbHost",
                "dbPortRef": "integration.dbPort",
                "dbNameRef": "integration.dbName",
                "flywayEnabled": true
              },
              "ports": {
                "pool": { "start": 18000, "end": 18999 },
                "services": [
                  { "key": "web", "protocol": "http", "containerPort": 5173, "env": "WEB_PORT" },
                  { "key": "api", "protocol": "http", "containerPort": 8080, "env": "SERVER_PORT" },
                  { "key": "admin", "protocol": "http", "containerPort": 8081, "env": "ADMIN_PORT" }
                ]
              },
              "middleware": {
                "redisKeyPrefixPattern": "{tenantId}:{productCode}:{reqNoSafe}:",
                "mqPrefixPattern": "{productCode}.{reqNoSafe}."
              },
              "integrationEnvDefault": "dev-integration",
              "envTemplates": [
                { "name": "SPRING_PROFILES_ACTIVE", "value": "sandbox" },
                { "name": "DB_SCHEMA", "value": "{schema}" },
                { "name": "REDIS_PREFIX", "value": "{redisKeyPrefix}" }
              ],
              "startTemplates": [
                { "key": "api", "actionHint": "start_maven", "workdir": "backend" },
                { "key": "web", "actionHint": "start_vite", "workdir": "frontend" }
              ],
              "cacheMounts": {
                "m2": true,
                "npm": true
              },
              "envVars": [
              ]
            }
            """;
}
