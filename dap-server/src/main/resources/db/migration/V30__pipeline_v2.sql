-- 自动化流水线 v2（可配置定义 + 运行时 + 需求分支 worktree）
-- 口径：一需求一条活跃实例；多需求可并行；同仓用 worktree+需求分支隔离，本期不合主干

ALTER TABLE sys_product_code_repo
    ADD COLUMN IF NOT EXISTS repo_kind VARCHAR(16) NOT NULL DEFAULT 'BIZ';

COMMENT ON COLUMN sys_product_code_repo.repo_kind IS 'BIZ=业务仓 TEST=测试仓 OTHER=其它';

-- —— 定义侧 ——
CREATE TABLE pipe_definition (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    version     INT          NOT NULL,
    name        VARCHAR(128) NOT NULL,
    tenant_id   BIGINT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_pipe_def_platform ON pipe_definition (code, version) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uk_pipe_def_tenant ON pipe_definition (tenant_id, code, version) WHERE tenant_id IS NOT NULL;

CREATE TABLE pipe_node_def (
    id              BIGSERIAL PRIMARY KEY,
    definition_id   BIGINT       NOT NULL REFERENCES pipe_definition (id) ON DELETE CASCADE,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    optional_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    parallel_group  VARCHAR(64),
    join_group      VARCHAR(64),
    sort_order      INT          NOT NULL DEFAULT 0,
    ext_json        TEXT,
    CONSTRAINT uk_pipe_node_def UNIQUE (definition_id, code)
);

CREATE TABLE pipe_unit_def (
    id                  BIGSERIAL PRIMARY KEY,
    node_def_id         BIGINT       NOT NULL REFERENCES pipe_node_def (id) ON DELETE CASCADE,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    position_code       VARCHAR(32)  NOT NULL,
    task_type_code      VARCHAR(64)  NOT NULL,
    round_tier          VARCHAR(8)   NOT NULL DEFAULT 'T48',
    max_round           INT          NOT NULL DEFAULT 48,
    repo_kind           VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    output_kind         VARCHAR(64),
    sort_order          INT          NOT NULL DEFAULT 0,
    ext_json            TEXT,
    CONSTRAINT uk_pipe_unit_def UNIQUE (node_def_id, code)
);

CREATE TABLE pipe_transition (
    id              BIGSERIAL PRIMARY KEY,
    definition_id   BIGINT       NOT NULL REFERENCES pipe_definition (id) ON DELETE CASCADE,
    from_node_code  VARCHAR(64)  NOT NULL,
    to_node_code    VARCHAR(64)  NOT NULL,
    event           VARCHAR(32)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    condition_json  TEXT
);

CREATE INDEX idx_pipe_transition_def ON pipe_transition (definition_id, from_node_code);

-- —— 运行时 ——
CREATE TABLE pipe_instance (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    requirement_id       BIGINT       NOT NULL REFERENCES req_requirement (id) ON DELETE CASCADE,
    definition_id        BIGINT       NOT NULL REFERENCES pipe_definition (id),
    status               VARCHAR(32)  NOT NULL,
    waiting_reason       VARCHAR(512),
    options_json         TEXT,
    release_version_id   BIGINT,
    release_time         TIMESTAMP,
    total_duration_ms    BIGINT       NOT NULL DEFAULT 0,
    total_tokens         BIGINT       NOT NULL DEFAULT 0,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_pipe_instance_req_active
    ON pipe_instance (requirement_id)
    WHERE status IN ('RUNNING', 'WAITING_HUMAN', 'SUSPENDED', 'FAILED');

CREATE INDEX idx_pipe_instance_tenant ON pipe_instance (tenant_id, status);

CREATE TABLE pipe_node_instance (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    node_code       VARCHAR(64)  NOT NULL,
    node_name       VARCHAR(128) NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    optional_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled_flag    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    CONSTRAINT uk_pipe_node_inst UNIQUE (instance_id, node_code)
);

CREATE TABLE pipe_unit_instance (
    id                  BIGSERIAL PRIMARY KEY,
    node_instance_id    BIGINT       NOT NULL REFERENCES pipe_node_instance (id) ON DELETE CASCADE,
    instance_id         BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    unit_code           VARCHAR(64)  NOT NULL,
    unit_name           VARCHAR(128) NOT NULL,
    position_code       VARCHAR(32)  NOT NULL,
    task_type_code      VARCHAR(64)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    round_tier          VARCHAR(8)   NOT NULL,
    max_round           INT          NOT NULL,
    confirm_round       INT          NOT NULL DEFAULT 0,
    repo_kind           VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    session_id          BIGINT,
    output_wiki_node_id BIGINT,
    error_message       VARCHAR(2000),
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT uk_pipe_unit_inst UNIQUE (node_instance_id, unit_code)
);

CREATE INDEX idx_pipe_unit_inst_session ON pipe_unit_instance (session_id);

CREATE TABLE pipe_confirm_round (
    id                  BIGSERIAL PRIMARY KEY,
    unit_instance_id    BIGINT       NOT NULL REFERENCES pipe_unit_instance (id) ON DELETE CASCADE,
    round_no            INT          NOT NULL,
    phase               VARCHAR(32)  NOT NULL,
    findings_json       TEXT,
    open_questions_json TEXT,
    human_answers_json  TEXT,
    wiki_node_id        BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at        TIMESTAMP,
    CONSTRAINT uk_pipe_confirm_round UNIQUE (unit_instance_id, round_no)
);

CREATE TABLE pipe_track_merge (
    id                   BIGSERIAL PRIMARY KEY,
    instance_id          BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    track_code           VARCHAR(64)  NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    llm_merge_json       TEXT,
    items_json           TEXT,
    merged_wiki_node_id  BIGINT,
    confirmed_by         BIGINT,
    confirmed_at         TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipe_track_merge UNIQUE (instance_id, track_code)
);

CREATE TABLE pipe_int_test_cycle (
    id                   BIGSERIAL PRIMARY KEY,
    instance_id          BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    unit_instance_id     BIGINT       NOT NULL REFERENCES pipe_unit_instance (id) ON DELETE CASCADE,
    cycle_no             INT          NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    report_wiki_node_id  BIGINT,
    fix_session_id       BIGINT,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    CONSTRAINT uk_pipe_int_cycle UNIQUE (unit_instance_id, cycle_no)
);

CREATE TABLE pipe_job (
    id                  BIGSERIAL PRIMARY KEY,
    instance_id         BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    unit_instance_id    BIGINT,
    job_type            VARCHAR(32)  NOT NULL,
    payload_json        TEXT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempts            INT          NOT NULL DEFAULT 0,
    max_attempts        INT          NOT NULL DEFAULT 5,
    next_run_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error          TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pipe_job_poll ON pipe_job (status, next_run_at);

CREATE TABLE pipe_artifact (
    id                  BIGSERIAL PRIMARY KEY,
    requirement_id      BIGINT       NOT NULL REFERENCES req_requirement (id) ON DELETE CASCADE,
    instance_id         BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    unit_instance_id    BIGINT,
    kind                VARCHAR(64)  NOT NULL,
    version             INT          NOT NULL,
    wiki_node_id        BIGINT,
    title               VARCHAR(256),
    is_current          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipe_artifact_ver UNIQUE (instance_id, kind, version)
);

-- 需求级 worktree 绑定（同仓多需求并行编码）
CREATE TABLE pipe_repo_worktree (
    id                      BIGSERIAL PRIMARY KEY,
    instance_id             BIGINT        NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    requirement_id          BIGINT        NOT NULL REFERENCES req_requirement (id) ON DELETE CASCADE,
    product_code_repo_id    BIGINT        NOT NULL REFERENCES sys_product_code_repo (id),
    repo_kind               VARCHAR(16)   NOT NULL,
    branch_name             VARCHAR(128)  NOT NULL,
    worktree_path           VARCHAR(1000) NOT NULL,
    base_local_path         VARCHAR(1000) NOT NULL,
    status                  VARCHAR(32)   NOT NULL DEFAULT 'READY',
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipe_repo_wt UNIQUE (instance_id, product_code_repo_id)
);

CREATE INDEX idx_pipe_repo_wt_req ON pipe_repo_worktree (requirement_id);

ALTER TABLE wb_chat_session
    ADD COLUMN IF NOT EXISTS pipe_unit_instance_id BIGINT;

COMMENT ON COLUMN wb_chat_session.pipe_unit_instance_id IS '流水线 Agent 单元实例';

INSERT INTO sys_permission (code, name, module, description) VALUES
('pipeline:view', '流水线查看', '自动化流水线', '查看需求流水线时间线'),
('pipeline:operate', '流水线操作', '自动化流水线', '启动/取消/确认等流水线操作'),
('pipeline:config', '流水线配置', '自动化流水线', '维护流水线定义');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission
WHERE code IN ('pipeline:view', 'pipeline:operate', 'pipeline:config')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = 1 AND rp.permission_id = sys_permission.id
  );

-- 平台种子定义 RD_AUTO v1
INSERT INTO pipe_definition (code, version, name, tenant_id, enabled)
VALUES ('RD_AUTO', 1, '研发自动化流水线', NULL, TRUE);

-- 节点
INSERT INTO pipe_node_def (definition_id, code, name, kind, optional_flag, parallel_group, join_group, sort_order)
SELECT d.id, n.code, n.name, n.kind, n.opt, n.pg, n.jg, n.so
FROM pipe_definition d
CROSS JOIN (VALUES
    ('pm_first',        '产品经理初审',           'HUMAN',    FALSE, NULL, NULL, 10),
    ('ai_pre',          'AI 初审',               'AI_MULTI', FALSE, NULL, NULL, 20),
    ('pm_second',       '产品经理二审',           'HUMAN',    FALSE, NULL, NULL, 30),
    ('gen_prd',         '生成需求分析',           'AI',       FALSE, NULL, NULL, 40),
    ('gen_arch',        '生成架构设计',           'AI',       TRUE,  'design', NULL, 50),
    ('gen_ui',          '生成 UI 设计',           'AI',       TRUE,  'design', NULL, 60),
    ('prd_review',      '需求设计PRD评审',        'AI_MULTI', FALSE, 'review_design', NULL, 70),
    ('arch_review',     '架构设计评审',           'AI_MULTI', TRUE,  'review_design', NULL, 80),
    ('ui_review',       'UI设计评审',            'AI_MULTI', TRUE,  'review_design', NULL, 90),
    ('doc_revise',      '文档修订',              'AI_MULTI', FALSE, NULL, NULL, 100),
    ('gen_detail',      '详细设计',              'AI',       FALSE, 'downstream', 'downstream', 110),
    ('gen_cases',       '测试用例',              'AI',       FALSE, 'downstream', 'downstream', 120),
    ('detail_review',   '详细设计评审',           'AI_MULTI', FALSE, 'review_dt', NULL, 130),
    ('cases_review',    '测试用例评审',           'AI_MULTI', FALSE, 'review_dt', NULL, 140),
    ('doc_revise_dt',   '文档修订（详设/用例）',   'AI_MULTI', FALSE, NULL, NULL, 150),
    ('gen_coding',      '开发编码',              'AI',       FALSE, NULL, NULL, 160),
    ('gen_int_test',    '集成测试',              'AI',       FALSE, NULL, NULL, 170),
    ('done',            '流水线结束',             'SYSTEM',   FALSE, NULL, NULL, 180)
) AS n(code, name, kind, opt, pg, jg, so)
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL;

-- 单元（核心槽位）
INSERT INTO pipe_unit_def (node_def_id, code, name, position_code, task_type_code, round_tier, max_round, repo_kind, output_kind, sort_order)
SELECT nd.id, u.code, u.name, u.pos, u.tt, u.tier, u.mr, u.rk, u.ok, u.so
FROM pipe_node_def nd
JOIN pipe_definition d ON d.id = nd.definition_id
CROSS JOIN LATERAL (
    SELECT * FROM (VALUES
        ('ai_pre', 'pre_biz', '业务初审', 'PM', 'REVIEW_PRE', 'T24', 24, 'NONE', 'PRE_BIZ', 1),
        ('ai_pre', 'pre_tech', '技术初审', 'ARCH', 'REVIEW_PRE_TECH', 'T24', 24, 'NONE', 'PRE_TECH', 2),
        ('gen_prd', 'gen_prd', '写 PRD', 'PM', 'GEN_PRD', 'T48', 48, 'NONE', 'PRD', 1),
        ('gen_arch', 'gen_arch', '写架构', 'ARCH', 'GEN_ARCH', 'T48', 48, 'NONE', 'ARCH', 1),
        ('gen_ui', 'gen_ui', '写 UI', 'UI', 'GEN_UI', 'T48', 48, 'NONE', 'UI_HTML', 1),
        ('prd_review', 'prd_rev_dev', 'PRD评·研发', 'DEV', 'REVIEW_PRD_DEV', 'T24', 24, 'BIZ', 'REVIEW_PRD_DEV', 1),
        ('prd_review', 'prd_rev_qa', 'PRD评·测试', 'QA', 'REVIEW_PRD_QA', 'T24', 24, 'NONE', 'REVIEW_PRD_QA', 2),
        ('arch_review', 'arch_rev_dev', '架构评·研发', 'DEV', 'REVIEW_ARCH_DEV', 'T24', 24, 'BIZ', 'REVIEW_ARCH_DEV', 1),
        ('arch_review', 'arch_rev_qa', '架构评·测试', 'QA', 'REVIEW_ARCH_QA', 'T24', 24, 'NONE', 'REVIEW_ARCH_QA', 2),
        ('ui_review', 'ui_rev_pm', 'UI评·产品', 'PM', 'REVIEW_UI', 'T24', 24, 'NONE', 'REVIEW_UI_PM', 1),
        ('ui_review', 'ui_rev_dev', 'UI评·研发', 'DEV', 'REVIEW_UI_DEV', 'T24', 24, 'BIZ', 'REVIEW_UI_DEV', 2),
        ('ui_review', 'ui_rev_qa', 'UI评·测试', 'QA', 'REVIEW_UI_QA', 'T24', 24, 'NONE', 'REVIEW_UI_QA', 3),
        ('doc_revise', 'rev_prd', '修订 PRD', 'PM', 'REVISE_PRD', 'T48', 48, 'NONE', 'PRD', 1),
        ('doc_revise', 'rev_arch', '修订架构', 'ARCH', 'REVISE_ARCH', 'T48', 48, 'NONE', 'ARCH', 2),
        ('doc_revise', 'rev_ui', '修订 UI', 'UI', 'REVISE_UI', 'T48', 48, 'NONE', 'UI_HTML', 3),
        ('gen_detail', 'gen_detail', '写详设', 'DEV', 'GEN_DETAIL', 'T48', 48, 'BIZ', 'DETAIL', 1),
        ('gen_cases', 'gen_cases', '写用例', 'QA', 'GEN_CASES', 'T48', 48, 'BIZ', 'CASES', 1),
        ('detail_review', 'detail_rev_arch', '详设评·架构', 'ARCH', 'REVIEW_DETAIL_ARCH', 'T24', 24, 'NONE', 'REVIEW_DETAIL_ARCH', 1),
        ('detail_review', 'detail_rev_pm', '详设评·产品', 'PM', 'REVIEW_DETAIL_PM', 'T24', 24, 'NONE', 'REVIEW_DETAIL_PM', 2),
        ('detail_review', 'detail_rev_qa', '详设评·测试', 'QA', 'REVIEW_DETAIL_QA', 'T24', 24, 'NONE', 'REVIEW_DETAIL_QA', 3),
        ('cases_review', 'case_rev_arch', '用例评·架构', 'ARCH', 'REVIEW_CASE_ARCH', 'T24', 24, 'NONE', 'REVIEW_CASE_ARCH', 1),
        ('cases_review', 'case_rev_pm', '用例评·产品', 'PM', 'REVIEW_CASE_PM', 'T24', 24, 'NONE', 'REVIEW_CASE_PM', 2),
        ('cases_review', 'case_rev_dev', '用例评·研发', 'DEV', 'REVIEW_CASE_DEV', 'T24', 24, 'BIZ', 'REVIEW_CASE_DEV', 3),
        ('doc_revise_dt', 'rev_detail', '修订详设', 'DEV', 'REVISE_DETAIL', 'T48', 48, 'BIZ', 'DETAIL', 1),
        ('doc_revise_dt', 'rev_cases', '修订用例', 'QA', 'REVISE_CASES', 'T48', 48, 'TEST', 'CASES', 2),
        ('gen_coding', 'coding', '按详设编码', 'DEV', 'GEN_CODING', 'T96', 96, 'BIZ', 'CODING_REPORT', 1),
        ('gen_int_test', 'int_test', '自动化集成覆盖', 'QA', 'GEN_INT_TEST', 'T96', 96, 'TEST', 'INT_TEST_REPORT', 1)
    ) AS x(node_code, code, name, pos, tt, tier, mr, rk, ok, so)
    WHERE x.node_code = nd.code
) AS u
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL;

-- 主路径转移（简化：串行主链；可选节点由运行时按 options 跳过）
INSERT INTO pipe_transition (definition_id, from_node_code, to_node_code, event, priority, condition_json)
SELECT d.id, t.frm, t.tto, t.evt, t.pri, t.cond
FROM pipe_definition d
CROSS JOIN (VALUES
    ('pm_first', 'ai_pre', 'SUCCESS', 0, NULL),
    ('ai_pre', 'pm_second', 'SUCCESS', 0, NULL),
    ('pm_second', 'gen_prd', 'APPROVE_DEV', 0, NULL),
    ('gen_prd', 'gen_arch', 'SUCCESS', 0, '{"option":"arch"}'),
    ('gen_prd', 'gen_ui', 'SUCCESS', 1, '{"option":"ui"}'),
    ('gen_prd', 'prd_review', 'SUCCESS', 2, NULL),
    ('gen_arch', 'prd_review', 'SUCCESS', 0, NULL),
    ('gen_ui', 'prd_review', 'SUCCESS', 0, NULL),
    ('gen_arch', 'arch_review', 'SUCCESS', 1, NULL),
    ('gen_ui', 'ui_review', 'SUCCESS', 1, NULL),
    ('prd_review', 'doc_revise', 'SUCCESS', 0, NULL),
    ('arch_review', 'doc_revise', 'SUCCESS', 0, NULL),
    ('ui_review', 'doc_revise', 'SUCCESS', 0, NULL),
    ('doc_revise', 'gen_detail', 'SUCCESS', 0, NULL),
    ('doc_revise', 'gen_cases', 'SUCCESS', 1, NULL),
    ('gen_detail', 'detail_review', 'JOIN_DOWNSTREAM', 0, NULL),
    ('gen_cases', 'cases_review', 'JOIN_DOWNSTREAM', 0, NULL),
    ('detail_review', 'doc_revise_dt', 'SUCCESS', 0, NULL),
    ('cases_review', 'doc_revise_dt', 'SUCCESS', 0, NULL),
    ('doc_revise_dt', 'gen_coding', 'SUCCESS', 0, NULL),
    ('gen_coding', 'gen_int_test', 'SUCCESS', 0, NULL),
    ('gen_int_test', 'done', 'SUCCESS', 0, NULL)
) AS t(frm, tto, evt, pri, cond)
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL;
