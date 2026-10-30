-- 需求任务类型（租户配置）
CREATE TABLE sys_req_task_type (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL,
    code                  VARCHAR(64)  NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    description           VARCHAR(500),
    require_deliverable   BOOLEAN      NOT NULL DEFAULT FALSE,
    deliverable_template  TEXT,
    status                SMALLINT     NOT NULL DEFAULT 1,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_req_task_type_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_rtt_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id)
);

CREATE INDEX idx_req_task_type_tenant ON sys_req_task_type (tenant_id);

-- 需求下的任务实例
CREATE TABLE req_requirement_task (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL,
    requirement_id        BIGINT       NOT NULL,
    task_type_id          BIGINT       NOT NULL,
    task_type_code        VARCHAR(64)  NOT NULL,
    task_type_name        VARCHAR(128) NOT NULL,
    require_deliverable   BOOLEAN      NOT NULL DEFAULT FALSE,
    handler_user_id       BIGINT       NOT NULL,
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
    CONSTRAINT fk_rrt_handler FOREIGN KEY (handler_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_rrt_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_rrt_requirement ON req_requirement_task (requirement_id, id);
CREATE INDEX idx_rrt_handler ON req_requirement_task (tenant_id, handler_user_id, status);

INSERT INTO sys_permission (code, name, module, description) VALUES
('req-task-type:view', '需求任务查看', '租户配置', '查看需求任务类型'),
('req-task-type:operate', '需求任务操作', '租户配置', '维护需求任务类型增删改');
