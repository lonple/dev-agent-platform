-- 任务支持多处理人 + 可选输入交付件（共享空间文件引用）
-- 不兼容历史任务数据：重建任务相关表

DROP TABLE IF EXISTS req_requirement_task CASCADE;

CREATE TABLE req_requirement_task (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL,
    requirement_id        BIGINT       NOT NULL,
    task_type_id          BIGINT       NOT NULL,
    task_type_code        VARCHAR(64)  NOT NULL,
    task_type_name        VARCHAR(128) NOT NULL,
    require_deliverable   BOOLEAN      NOT NULL DEFAULT FALSE,
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    remark                VARCHAR(1000),
    deliverable_text      VARCHAR(2000),
    created_by            BIGINT       NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at          TIMESTAMP,
    CONSTRAINT ck_rrt_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT fk_rrt_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_rrt_req FOREIGN KEY (requirement_id) REFERENCES req_requirement (id) ON DELETE CASCADE,
    CONSTRAINT fk_rrt_type FOREIGN KEY (task_type_id) REFERENCES sys_req_task_type (id),
    CONSTRAINT fk_rrt_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_rrt_requirement ON req_requirement_task (requirement_id, id);

CREATE TABLE req_requirement_task_handler (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT    NOT NULL,
    task_id         BIGINT    NOT NULL,
    user_id         BIGINT    NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rrth_task_user UNIQUE (task_id, user_id),
    CONSTRAINT fk_rrth_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_rrth_task FOREIGN KEY (task_id) REFERENCES req_requirement_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_rrth_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_rrth_user ON req_requirement_task_handler (tenant_id, user_id);
CREATE INDEX idx_rrth_task ON req_requirement_task_handler (task_id);

-- 输入交付件：可选，引用共享空间中的文件节点
CREATE TABLE req_requirement_task_input (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL,
    task_id         BIGINT        NOT NULL,
    workspace_id    BIGINT        NOT NULL,
    node_id         BIGINT        NOT NULL,
    display_path    VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rrti_task_node UNIQUE (task_id, workspace_id, node_id),
    CONSTRAINT fk_rrti_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_rrti_task FOREIGN KEY (task_id) REFERENCES req_requirement_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_rrti_ws FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id),
    CONSTRAINT fk_rrti_node FOREIGN KEY (node_id) REFERENCES sys_workspace_node (id)
);

CREATE INDEX idx_rrti_task ON req_requirement_task_input (task_id);
