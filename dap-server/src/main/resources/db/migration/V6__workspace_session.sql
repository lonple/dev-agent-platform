-- 工作空间会话：挂在个人空间下；不兼容旧聊天会话数据
DROP TABLE IF EXISTS wb_chat_message CASCADE;
DROP TABLE IF EXISTS wb_chat_session CASCADE;
DROP TABLE IF EXISTS wb_session_context CASCADE;

CREATE TABLE wb_chat_session (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    workspace_id         BIGINT       NOT NULL,
    session_type         VARCHAR(16)  NOT NULL,
    title                VARCHAR(128) NOT NULL,
    requirement_id       BIGINT,
    task_id              BIGINT,
    output_dir_node_id   BIGINT,
    position_id          BIGINT,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_wcs_type CHECK (session_type IN ('NORMAL', 'TASK')),
    CONSTRAINT fk_wcs_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_wcs_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_wcs_ws FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_wcs_req FOREIGN KEY (requirement_id) REFERENCES req_requirement (id) ON DELETE SET NULL,
    CONSTRAINT fk_wcs_task FOREIGN KEY (task_id) REFERENCES req_requirement_task (id) ON DELETE SET NULL,
    CONSTRAINT fk_wcs_out_dir FOREIGN KEY (output_dir_node_id) REFERENCES sys_workspace_node (id) ON DELETE SET NULL,
    CONSTRAINT fk_wcs_position FOREIGN KEY (position_id) REFERENCES sys_position (id)
);

CREATE INDEX idx_wcs_workspace ON wb_chat_session (workspace_id, updated_at DESC);
CREATE INDEX idx_wcs_user ON wb_chat_session (tenant_id, user_id, updated_at DESC);

CREATE TABLE wb_session_context (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    workspace_id    BIGINT        NOT NULL,
    node_id         BIGINT        NOT NULL,
    node_type       VARCHAR(16)   NOT NULL,
    display_path    VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wsc_session_node UNIQUE (session_id, workspace_id, node_id),
    CONSTRAINT ck_wsc_node_type CHECK (node_type IN ('DIR', 'FILE')),
    CONSTRAINT fk_wsc_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsc_ws FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_wsc_node FOREIGN KEY (node_id) REFERENCES sys_workspace_node (id) ON DELETE CASCADE
);

CREATE INDEX idx_wsc_session ON wb_session_context (session_id);

CREATE TABLE wb_chat_message (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT       NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    thinking        TEXT,
    content         TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'done',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE CASCADE
);

CREATE INDEX idx_wb_chat_message_session ON wb_chat_message (session_id, id);
