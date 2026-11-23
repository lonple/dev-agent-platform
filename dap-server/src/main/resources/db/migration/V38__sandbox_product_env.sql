-- 产品级沙箱环境变量（自由配置，docker run -e 注入；不绑死对接环境结构）

CREATE TABLE IF NOT EXISTS sb_product_env_var (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL,
    product_id  BIGINT       NOT NULL,
    env_name    VARCHAR(128) NOT NULL,
    env_value   TEXT         NOT NULL DEFAULT '',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    remark      VARCHAR(256),
    sort_no     INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sb_product_env_var UNIQUE (product_id, env_name)
);

CREATE INDEX IF NOT EXISTS idx_sb_product_env_product ON sb_product_env_var(product_id);
