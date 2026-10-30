-- 工作 Wiki：租户级公共文件库；会话上下文改为引用 Wiki 节点
-- 不做历史兼容：清空共享工作空间与旧会话上下文

DELETE FROM wb_session_context;
DELETE FROM wb_chat_message;
DELETE FROM wb_chat_session;
DELETE FROM req_requirement_task_input;
DELETE FROM sys_workspace_node WHERE workspace_id IN (
    SELECT id FROM sys_workspace WHERE space_type = 'SHARED'
);
DELETE FROM sys_workspace WHERE space_type = 'SHARED';

CREATE TABLE IF NOT EXISTS sys_wiki_node (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    parent_id       BIGINT,
    node_type       VARCHAR(16)  NOT NULL,
    display_name    VARCHAR(200) NOT NULL,
    storage_name    VARCHAR(64)  NOT NULL,
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_wiki_node_type CHECK (node_type IN ('DIR', 'FILE')),
    CONSTRAINT fk_wiki_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_wiki_parent FOREIGN KEY (parent_id) REFERENCES sys_wiki_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_wiki_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX IF NOT EXISTS idx_wiki_node_tenant ON sys_wiki_node (tenant_id, parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_node_sibling_name
    ON sys_wiki_node (tenant_id, (COALESCE(parent_id, 0)), display_name);

DROP TABLE IF EXISTS wb_session_context CASCADE;

CREATE TABLE wb_session_context (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    wiki_node_id    BIGINT        NOT NULL,
    node_type       VARCHAR(16)   NOT NULL,
    display_path    VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wsc_session_wiki_node UNIQUE (session_id, wiki_node_id),
    CONSTRAINT ck_wsc_wiki_node_type CHECK (node_type IN ('DIR', 'FILE')),
    CONSTRAINT fk_wsc_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsc_wiki_node FOREIGN KEY (wiki_node_id) REFERENCES sys_wiki_node (id) ON DELETE CASCADE
);

CREATE INDEX idx_wsc_session ON wb_session_context (session_id);

-- 任务输入交付件改为引用 Wiki 文件
DROP TABLE IF EXISTS req_requirement_task_input CASCADE;

CREATE TABLE req_requirement_task_input (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL,
    task_id         BIGINT        NOT NULL,
    wiki_node_id    BIGINT        NOT NULL,
    display_path    VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rrti_task_wiki_node UNIQUE (task_id, wiki_node_id),
    CONSTRAINT fk_rrti_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_rrti_task FOREIGN KEY (task_id) REFERENCES req_requirement_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_rrti_wiki_node FOREIGN KEY (wiki_node_id) REFERENCES sys_wiki_node (id) ON DELETE CASCADE
);

CREATE INDEX idx_rrti_task ON req_requirement_task_input (task_id);
