-- 运行时 Profile、端口占用、lease 扩展；默认镜像改为 webdev

ALTER TABLE sb_product_resource
    ALTER COLUMN image SET DEFAULT 'dap-sandbox-webdev:dev';

UPDATE sb_product_resource
SET image = 'dap-sandbox-webdev:dev'
WHERE image = 'dap-code-tools:dev' OR image IS NULL OR image = '';

CREATE TABLE IF NOT EXISTS sb_runtime_profile (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    product_id    BIGINT       NOT NULL,
    profile_code  VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(128),
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    config_json   TEXT         NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    version       INT          NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sb_runtime_profile UNIQUE (product_id, profile_code)
);

CREATE INDEX IF NOT EXISTS idx_sb_runtime_profile_product ON sb_runtime_profile(product_id);

ALTER TABLE sb_lease
    ADD COLUMN IF NOT EXISTS profile_id BIGINT REFERENCES sb_runtime_profile(id),
    ADD COLUMN IF NOT EXISTS profile_version INT,
    ADD COLUMN IF NOT EXISTS integration_env_code VARCHAR(64);

CREATE TABLE IF NOT EXISTS sb_port_allocation (
    id          BIGSERIAL PRIMARY KEY,
    lease_id    BIGINT       NOT NULL REFERENCES sb_lease(id),
    node_id     BIGINT,
    service_key VARCHAR(64)  NOT NULL,
    host_port   INT          NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sb_port_lease ON sb_port_allocation(lease_id);
CREATE INDEX IF NOT EXISTS idx_sb_port_active ON sb_port_allocation(status, host_port);
