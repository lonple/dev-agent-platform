-- =============================================================================
-- DAP 平台初始化 schema + 种子数据（由原 V1–V12 合并）
-- =============================================================================

CREATE TABLE sys_role (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL,
    description     VARCHAR(200),
    builtin         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_name UNIQUE (name)
);

CREATE TABLE sys_permission (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    module          VARCHAR(64)  NOT NULL,
    description     VARCHAR(200),
    CONSTRAINT uk_sys_permission_code UNIQUE (code)
);

CREATE TABLE sys_role_permission (
    role_id         BIGINT NOT NULL,
    permission_id   BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission (id)
);

CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(32)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    description     VARCHAR(200),
    role_id         BIGINT       NOT NULL,
    builtin         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
);

CREATE TABLE sys_tenant (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    description     VARCHAR(200),
    status          SMALLINT     NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_tenant_code UNIQUE (code)
);

CREATE TABLE sys_tenant_user (
    tenant_id       BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    tenant_role     VARCHAR(32)  NOT NULL DEFAULT 'OPERATOR',
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT fk_tu_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_tu_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_sys_user_role_id ON sys_user (role_id);
CREATE INDEX idx_sys_tenant_user_user ON sys_tenant_user (user_id);

-- 岗位与技能
CREATE TABLE sys_position (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    description     VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_position_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_position_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id)
);

CREATE TABLE sys_position_skill (
    id              BIGSERIAL PRIMARY KEY,
    position_id     BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    description     VARCHAR(200),
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_position_skill_name UNIQUE (position_id, name),
    CONSTRAINT fk_skill_position FOREIGN KEY (position_id) REFERENCES sys_position (id)
);

-- 用户在租户下的岗位/技能上下文（技能多选见关联表）
CREATE TABLE sys_user_tenant_context (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    position_id         BIGINT,
    custom_skill_text   VARCHAR(128),
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_tenant_ctx UNIQUE (tenant_id, user_id),
    CONSTRAINT fk_utc_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_utc_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_utc_position FOREIGN KEY (position_id) REFERENCES sys_position (id)
);

CREATE TABLE sys_user_tenant_context_skill (
    context_id BIGINT NOT NULL,
    skill_id   BIGINT NOT NULL,
    PRIMARY KEY (context_id, skill_id),
    CONSTRAINT fk_utcs_ctx FOREIGN KEY (context_id) REFERENCES sys_user_tenant_context (id) ON DELETE CASCADE,
    CONSTRAINT fk_utcs_skill FOREIGN KEY (skill_id) REFERENCES sys_position_skill (id)
);

-- 工作台：会话与消息
CREATE TABLE wb_chat_session (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    position_id     BIGINT,
    user_id         BIGINT NOT NULL,
    title           VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_session_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_session_position FOREIGN KEY (position_id) REFERENCES sys_position (id),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_wb_chat_session_scope ON wb_chat_session (tenant_id, user_id, updated_at DESC);

CREATE TABLE wb_chat_message (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    role            VARCHAR(16) NOT NULL,
    thinking        TEXT,
    content         TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'done',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE CASCADE
);

CREATE INDEX idx_wb_chat_message_session ON wb_chat_message (session_id, id);

-- 租户工作空间（磁盘目录）：SHARED=租户配置共享，PERSONAL=用户个人
CREATE TABLE sys_workspace (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    space_type      VARCHAR(32)  NOT NULL DEFAULT 'SHARED',
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(500),
    owner_user_id   BIGINT       NOT NULL,
    work_dir        VARCHAR(256) NOT NULL,
    status          SMALLINT     NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_workspace_tenant_type_name UNIQUE (tenant_id, space_type, name),
    CONSTRAINT uk_sys_workspace_tenant_dir UNIQUE (tenant_id, work_dir),
    CONSTRAINT fk_sys_workspace_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_sys_workspace_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_sys_workspace_tenant ON sys_workspace (tenant_id);
CREATE INDEX idx_sys_workspace_type ON sys_workspace (tenant_id, space_type);

-- 用户引入的共享工作空间（出现在个人侧）
CREATE TABLE sys_workspace_intro (
    tenant_id       BIGINT    NOT NULL,
    user_id         BIGINT    NOT NULL,
    workspace_id    BIGINT    NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, workspace_id),
    CONSTRAINT fk_wsi_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_wsi_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_wsi_ws FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE
);

CREATE INDEX idx_workspace_intro_user ON sys_workspace_intro (tenant_id, user_id);

-- 产品与版本
CREATE TABLE sys_product (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    description       VARCHAR(500),
    manager_user_id   BIGINT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_product_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT uk_sys_product_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_product_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_product_manager FOREIGN KEY (manager_user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_sys_product_tenant ON sys_product (tenant_id);

CREATE TABLE sys_product_version (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL,
    product_id       BIGINT       NOT NULL,
    version          VARCHAR(64)  NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'PLANNING',
    plan_release_at  TIMESTAMP,
    description      VARCHAR(500),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_version UNIQUE (product_id, version),
    CONSTRAINT fk_pv_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pv_product FOREIGN KEY (product_id) REFERENCES sys_product (id) ON DELETE CASCADE
);

CREATE INDEX idx_product_version_product ON sys_product_version (product_id);

-- 需求
CREATE TABLE req_requirement (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL,
    req_no                VARCHAR(64)  NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    product_id            BIGINT       NOT NULL,
    product_version_id    BIGINT,
    product_version       VARCHAR(64)  NOT NULL,
    description           TEXT         NOT NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    approver_id           BIGINT,
    current_handler_id    BIGINT,
    reject_reason         VARCHAR(1000),
    plan_date             DATE,
    release_version       VARCHAR(64),
    created_by            BIGINT       NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_req_requirement_no UNIQUE (req_no),
    CONSTRAINT fk_req_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_req_product FOREIGN KEY (product_id) REFERENCES sys_product (id),
    CONSTRAINT fk_req_product_version FOREIGN KEY (product_version_id) REFERENCES sys_product_version (id),
    CONSTRAINT fk_req_creator FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT fk_req_approver FOREIGN KEY (approver_id) REFERENCES sys_user (id),
    CONSTRAINT fk_req_handler FOREIGN KEY (current_handler_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_req_requirement_tenant ON req_requirement (tenant_id, created_at DESC);
CREATE INDEX idx_req_requirement_product ON req_requirement (product_id);
CREATE INDEX idx_req_requirement_status ON req_requirement (tenant_id, status);
CREATE INDEX idx_req_handler_status ON req_requirement (tenant_id, current_handler_id, status);
CREATE INDEX idx_req_product_version ON req_requirement (product_version_id);
CREATE INDEX idx_req_requirement_no ON req_requirement (tenant_id, req_no);

CREATE TABLE req_requirement_flow (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL,
    requirement_id   BIGINT       NOT NULL,
    node_type        VARCHAR(32)  NOT NULL,
    from_status      VARCHAR(32),
    to_status        VARCHAR(32),
    operator_id      BIGINT       NOT NULL,
    comment_text     VARCHAR(2000),
    extra_json       TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_flow_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_flow_req FOREIGN KEY (requirement_id) REFERENCES req_requirement (id) ON DELETE CASCADE,
    CONSTRAINT fk_flow_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_req_flow_req ON req_requirement_flow (requirement_id, id);

CREATE TABLE req_requirement_handler (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT    NOT NULL,
    requirement_id   BIGINT    NOT NULL,
    user_id          BIGINT    NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_req_handler UNIQUE (requirement_id, user_id),
    CONSTRAINT fk_req_handler_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_req_handler_req FOREIGN KEY (requirement_id) REFERENCES req_requirement (id) ON DELETE CASCADE,
    CONSTRAINT fk_req_handler_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_req_handler_user ON req_requirement_handler (tenant_id, user_id);
CREATE INDEX idx_req_handler_req ON req_requirement_handler (requirement_id);

CREATE TABLE req_requirement_no_seq (
    tenant_id   BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    year_no     INT    NOT NULL,
    last_seq    INT    NOT NULL DEFAULT -1,
    PRIMARY KEY (tenant_id, product_id, year_no),
    CONSTRAINT fk_req_no_seq_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_req_no_seq_product FOREIGN KEY (product_id) REFERENCES sys_product (id) ON DELETE CASCADE
);

-- =============================================================================
-- 种子：权限、角色、管理员（不预置租户/产品/需求）
-- =============================================================================

INSERT INTO sys_permission (code, name, module, description) VALUES
('user:view', '用户查看', '用户管理', '查看平台用户列表与详情'),
('user:operate', '用户操作', '用户管理', '创建、编辑、删除平台用户'),
('role:view', '角色查看', '角色管理', '查看角色列表与详情'),
('role:operate', '角色操作', '角色管理', '创建、编辑、删除角色及权限配置'),
('permission:view', '权限查看', '权限管理', '查看权限列表'),
('permission:operate', '权限操作', '权限管理', '内置保留，不授予'),
('tenant:view', '租户查看', '租户管理', '查看租户列表与管理员'),
('tenant:operate', '租户操作', '租户管理', '创建、编辑、删除租户及配置租户管理员'),
('tenant-user:view', '租户用户查看', '租户配置', '查看当前租户下用户'),
('tenant-user:operate', '租户用户操作', '租户配置', '维护当前租户下用户增删改'),
('position:view', '岗位查看', '租户配置', '查看岗位与技能'),
('position:operate', '岗位操作', '租户配置', '岗位与技能增删改'),
('product:view', '产品查看', '租户配置', '查看产品列表与详情'),
('product:operate', '产品操作', '租户配置', '产品增删改'),
('workspace:view', '工作空间查看', '租户配置', '查看租户工作空间'),
('workspace:operate', '工作空间操作', '租户配置', '工作空间增删改'),
('requirement:view', '需求查看', '需求管理', '查看与提交需求'),
('requirement:operate', '需求操作', '需求管理', '需求增删改'),
('work:access', '工作权限', '工作台', '进入工作台');

-- 平台角色（租户角色存在 sys_tenant_user.tenant_role，不在此表）
INSERT INTO sys_role (id, name, description, builtin) VALUES
(1, '系统管理员', '具备用户中心全部权限', TRUE),
(2, '系统操作人员', '仅可访问首页', TRUE);

SELECT setval('sys_role_id_seq', 2);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission
WHERE code IN (
    'user:view', 'user:operate',
    'role:view', 'role:operate',
    'permission:view',
    'tenant:view', 'tenant:operate'
);

-- 系统操作人员：无额外权限（仅首页）

-- 密码 admin，BCrypt
INSERT INTO sys_user (username, password_hash, description, role_id, builtin) VALUES
('admin', '$2b$10$vTaHIdeJNzu0hyrjQfHYJuRq50pTil4Z9syKFKn2PqG3fcr17OCA6', '内置系统管理员', 1, TRUE);
