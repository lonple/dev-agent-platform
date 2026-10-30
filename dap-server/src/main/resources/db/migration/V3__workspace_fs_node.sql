-- 工作空间磁盘节点映射：页面显示名 vs 磁盘存储名
CREATE TABLE sys_workspace_node (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    workspace_id    BIGINT       NOT NULL,
    parent_id       BIGINT,
    node_type       VARCHAR(16)  NOT NULL,
    display_name    VARCHAR(256) NOT NULL,
    storage_name    VARCHAR(128) NOT NULL,
    requirement_id  BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_wsn_type CHECK (node_type IN ('DIR', 'FILE')),
    CONSTRAINT fk_wsn_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_wsn_ws FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsn_parent FOREIGN KEY (parent_id) REFERENCES sys_workspace_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsn_req FOREIGN KEY (requirement_id) REFERENCES req_requirement (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_wsn_ws_parent_display
    ON sys_workspace_node (workspace_id, COALESCE(parent_id, 0), display_name);

CREATE UNIQUE INDEX uk_wsn_ws_parent_storage
    ON sys_workspace_node (workspace_id, COALESCE(parent_id, 0), storage_name);

CREATE INDEX idx_wsn_workspace ON sys_workspace_node (workspace_id, parent_id);
CREATE INDEX idx_wsn_requirement ON sys_workspace_node (workspace_id, requirement_id);
