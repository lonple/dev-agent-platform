-- Docker 沙箱最小闭环：节点 / 产品资源桶 / 租约

CREATE TABLE IF NOT EXISTS sb_node (
    id              BIGSERIAL PRIMARY KEY,
    node_code       VARCHAR(64)  NOT NULL,
    display_name    VARCHAR(128),
    client_base_url VARCHAR(512) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ONLINE',
    last_health_at  TIMESTAMP,
    docker_ok       BOOLEAN      NOT NULL DEFAULT FALSE,
    labels_json     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sb_node_code UNIQUE (node_code)
);

CREATE TABLE IF NOT EXISTS sb_product_resource (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    product_id    BIGINT       NOT NULL,
    resource_code VARCHAR(64)  NOT NULL,
    node_id       BIGINT       NOT NULL REFERENCES sb_node(id),
    image         VARCHAR(256) NOT NULL DEFAULT 'dap-code-tools:dev',
    max_slots     INT          NOT NULL DEFAULT 3,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sb_product_resource UNIQUE (product_id, resource_code)
);

CREATE TABLE IF NOT EXISTS sb_lease (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    product_id           BIGINT       NOT NULL,
    resource_id          BIGINT       NOT NULL REFERENCES sb_product_resource(id),
    req_no               VARCHAR(128),
    session_id           BIGINT,
    job_id               BIGINT,
    worktree_host_path   VARCHAR(1024) NOT NULL,
    purpose              VARCHAR(64)  NOT NULL DEFAULT 'DEV',
    status               VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    runtime_instance_json TEXT,
    expires_at           TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    closed_at            TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sb_lease_resource_status ON sb_lease(resource_id, status);
CREATE INDEX IF NOT EXISTS idx_sb_lease_session ON sb_lease(session_id);

-- 本机默认节点（可按环境改 URL）
INSERT INTO sb_node(node_code, display_name, client_base_url, status, docker_ok)
VALUES ('local-dev', 'Local Docker Desktop', 'http://127.0.0.1:9300', 'ONLINE', FALSE)
ON CONFLICT (node_code) DO NOTHING;
