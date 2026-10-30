-- 个人工作空间关联共享工作空间（替换「用户引入共享」模型）
DROP TABLE IF EXISTS sys_workspace_intro;

CREATE TABLE IF NOT EXISTS sys_workspace_assoc (
    tenant_id               BIGINT    NOT NULL,
    personal_workspace_id   BIGINT    NOT NULL,
    shared_workspace_id     BIGINT    NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (personal_workspace_id, shared_workspace_id),
    CONSTRAINT fk_wsa_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_wsa_personal FOREIGN KEY (personal_workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsa_shared FOREIGN KEY (shared_workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workspace_assoc_personal
    ON sys_workspace_assoc (tenant_id, personal_workspace_id);

CREATE INDEX IF NOT EXISTS idx_workspace_assoc_shared
    ON sys_workspace_assoc (tenant_id, shared_workspace_id);
