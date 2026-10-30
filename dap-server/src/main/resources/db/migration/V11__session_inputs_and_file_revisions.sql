-- 任务会话输入文件（Wiki 文件节点）+ 个人空间文件修订快照（写前留旧版）

CREATE TABLE IF NOT EXISTS wb_session_input (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    wiki_node_id    BIGINT        NOT NULL,
    display_path    VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wsi_session_wiki UNIQUE (session_id, wiki_node_id),
    CONSTRAINT fk_wsi_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsi_wiki FOREIGN KEY (wiki_node_id) REFERENCES sys_wiki_node (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wsi_session ON wb_session_input (session_id);

CREATE TABLE IF NOT EXISTS sys_workspace_node_revision (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL,
    workspace_id    BIGINT        NOT NULL,
    node_id         BIGINT        NOT NULL,
    version_no      INT           NOT NULL,
    content         TEXT          NOT NULL,
    session_id      BIGINT,
    created_by      BIGINT,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wnr_node_ver UNIQUE (node_id, version_no),
    CONSTRAINT fk_wnr_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_wnr_ws FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_wnr_node FOREIGN KEY (node_id) REFERENCES sys_workspace_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_wnr_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE SET NULL,
    CONSTRAINT fk_wnr_user FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX IF NOT EXISTS idx_wnr_node ON sys_workspace_node_revision (node_id, version_no DESC);
