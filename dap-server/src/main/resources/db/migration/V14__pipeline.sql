-- 自动化研发流水线（定义 + 运行时 + 步骤耗时/Token）

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

CREATE TABLE pipe_step_def (
    id                   BIGSERIAL PRIMARY KEY,
    definition_id        BIGINT       NOT NULL REFERENCES pipe_definition (id) ON DELETE CASCADE,
    code                 VARCHAR(64)  NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    stage                VARCHAR(32)  NOT NULL,
    executor             VARCHAR(32)  NOT NULL,
    role_pack_code       VARCHAR(64),
    task_type_code       VARCHAR(64),
    template_wiki_path   VARCHAR(512),
    input_artifact_kinds TEXT,
    output_artifact_kind VARCHAR(64),
    parallel_group       VARCHAR(64),
    join_group           VARCHAR(64),
    max_round            INT          NOT NULL DEFAULT 3,
    auto_advance         BOOLEAN      NOT NULL DEFAULT TRUE,
    human_role           VARCHAR(32),
    assignee_policy      VARCHAR(32)  NOT NULL DEFAULT 'ANY',
    assignee_quorum      INT,
    reject_policy        VARCHAR(32)  NOT NULL DEFAULT 'ANY_REJECT',
    on_reject            VARCHAR(32),
    sort_order           INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_pipe_step_def UNIQUE (definition_id, code)
);

CREATE TABLE pipe_transition (
    id              BIGSERIAL PRIMARY KEY,
    definition_id   BIGINT      NOT NULL REFERENCES pipe_definition (id) ON DELETE CASCADE,
    from_step_code  VARCHAR(64) NOT NULL,
    to_step_code    VARCHAR(64) NOT NULL,
    event           VARCHAR(32) NOT NULL,
    priority        INT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_pipe_transition_def ON pipe_transition (definition_id, from_step_code);

CREATE TABLE pipe_instance (
    id                   BIGSERIAL PRIMARY KEY,
    requirement_id       BIGINT       NOT NULL REFERENCES req_requirement (id) ON DELETE CASCADE,
    definition_id        BIGINT       NOT NULL REFERENCES pipe_definition (id),
    status               VARCHAR(32)  NOT NULL,
    waiting_reason       VARCHAR(256),
    current_step_codes   TEXT,
    total_duration_ms    BIGINT       NOT NULL DEFAULT 0,
    total_prompt_tokens  BIGINT       NOT NULL DEFAULT 0,
    total_completion_tokens BIGINT    NOT NULL DEFAULT 0,
    total_tokens         BIGINT       NOT NULL DEFAULT 0,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipe_instance_req UNIQUE (requirement_id)
);

CREATE TABLE pipe_step_instance (
    id                   BIGSERIAL PRIMARY KEY,
    instance_id          BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    step_code            VARCHAR(64)  NOT NULL,
    step_name            VARCHAR(128) NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    round                INT          NOT NULL DEFAULT 1,
    executor             VARCHAR(32)  NOT NULL,
    handler_user_id      BIGINT,
    task_id              BIGINT,
    session_id           BIGINT,
    output_artifact_id   BIGINT,
    review_id            BIGINT,
    error_message        VARCHAR(2000),
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    duration_ms          BIGINT       NOT NULL DEFAULT 0,
    prompt_tokens        BIGINT       NOT NULL DEFAULT 0,
    completion_tokens    BIGINT       NOT NULL DEFAULT 0,
    total_tokens         BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pipe_step_inst ON pipe_step_instance (instance_id, step_code, round);
CREATE INDEX idx_pipe_step_session ON pipe_step_instance (session_id);

CREATE TABLE pipe_step_assignee (
    id                BIGSERIAL PRIMARY KEY,
    step_instance_id  BIGINT      NOT NULL REFERENCES pipe_step_instance (id) ON DELETE CASCADE,
    user_id           BIGINT      NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    acted_at          TIMESTAMP,
    comment_text      VARCHAR(2000),
    CONSTRAINT uk_pipe_step_assignee UNIQUE (step_instance_id, user_id)
);

CREATE TABLE pipe_artifact (
    id                 BIGSERIAL PRIMARY KEY,
    requirement_id     BIGINT       NOT NULL REFERENCES req_requirement (id) ON DELETE CASCADE,
    kind               VARCHAR(64)  NOT NULL,
    version            INT          NOT NULL,
    wiki_node_id       BIGINT,
    step_instance_id   BIGINT,
    title              VARCHAR(256),
    is_current         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipe_artifact_ver UNIQUE (requirement_id, kind, version)
);

CREATE TABLE pipe_review (
    id                     BIGSERIAL PRIMARY KEY,
    step_instance_id       BIGINT      NOT NULL REFERENCES pipe_step_instance (id) ON DELETE CASCADE,
    requirement_id         BIGINT      NOT NULL REFERENCES req_requirement (id) ON DELETE CASCADE,
    status                 VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    summary_wiki_node_id   BIGINT,
    confirmed_by           BIGINT,
    confirmed_at           TIMESTAMP,
    created_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pipe_review_item (
    id               BIGSERIAL PRIMARY KEY,
    review_id        BIGINT       NOT NULL REFERENCES pipe_review (id) ON DELETE CASCADE,
    ordinal          INT          NOT NULL DEFAULT 0,
    content          TEXT         NOT NULL,
    checked          BOOLEAN      NOT NULL DEFAULT FALSE,
    source           VARCHAR(16)  NOT NULL DEFAULT 'AI',
    amend_user_id    BIGINT,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pipe_job (
    id                 BIGSERIAL PRIMARY KEY,
    instance_id        BIGINT       NOT NULL REFERENCES pipe_instance (id) ON DELETE CASCADE,
    step_instance_id   BIGINT,
    job_type           VARCHAR(32)  NOT NULL,
    payload_json       TEXT,
    status             VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempts           INT          NOT NULL DEFAULT 0,
    max_attempts       INT          NOT NULL DEFAULT 5,
    next_run_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error         TEXT,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pipe_job_poll ON pipe_job (status, next_run_at);

-- 会话可关联流水线步骤（便于回写 token）
ALTER TABLE wb_chat_session ADD COLUMN IF NOT EXISTS pipe_step_instance_id BIGINT;

-- 任务来源
ALTER TABLE req_requirement_task ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE req_requirement_task ADD COLUMN IF NOT EXISTS pipe_step_instance_id BIGINT;

-- 种子：平台默认定义 RD_AUTO v1
INSERT INTO pipe_definition (code, version, name, tenant_id, enabled)
VALUES ('RD_AUTO', 1, '研发自动化流水线', NULL, TRUE);

INSERT INTO pipe_step_def (definition_id, code, name, stage, executor, role_pack_code, human_role, assignee_policy, auto_advance, sort_order, output_artifact_kind)
SELECT d.id, s.code, s.name, s.stage, s.executor, s.role_pack, s.human_role, s.policy, s.auto_adv, s.sort_order, s.out_kind
FROM pipe_definition d
CROSS JOIN (VALUES
    ('PM_FIRST_APPROVE', '产品经理初审', 'INTAKE', 'HUMAN_APPROVE', NULL, 'PM', 'ANY', FALSE, 10, NULL),
    ('AI_PRE_REVIEW', 'AI初审', 'INTAKE', 'AI_REVIEW', 'AI_PM', NULL, 'ANY', TRUE, 20, 'PRE_REVIEW'),
    ('PM_PRE_REVIEW_CONFIRM', '初审有效确认', 'INTAKE', 'HUMAN_APPROVE', NULL, 'PM', 'ANY', FALSE, 30, NULL),
    ('PM_SECOND_APPROVE', '产品经理二审', 'INTAKE', 'HUMAN_APPROVE', NULL, 'PM', 'ANY', FALSE, 40, NULL),
    ('GEN_PRD_V1', '需求分析', 'ANALYSIS', 'AI_GENERATE', 'AI_PM', NULL, 'ANY', TRUE, 50, 'PRD'),
    ('REVIEW_PRD_ARCH', '需求分析初审(AI)', 'ANALYSIS', 'AI_REVIEW', 'AI_ARCH', NULL, 'ANY', TRUE, 60, 'PRD_REVIEW_ARCH'),
    ('CONFIRM_PRD_ARCH', '架构师确认初审', 'ANALYSIS', 'HUMAN_APPROVE', NULL, 'ARCH', 'ALL', FALSE, 70, NULL),
    ('GEN_ARCH_V1', '架构设计', 'DESIGN', 'AI_GENERATE', 'AI_ARCH', NULL, 'ANY', TRUE, 80, 'ARCH'),
    ('GEN_UI_V1', 'UI设计说明', 'DESIGN', 'AI_GENERATE', 'AI_UI', NULL, 'ANY', TRUE, 90, 'UI_SPEC'),
    ('GEN_DETAIL_V1', '详细设计', 'DETAIL', 'AI_GENERATE', 'AI_DEV', NULL, 'ANY', TRUE, 100, 'DETAIL'),
    ('GEN_CASES_V1', '测试用例', 'DETAIL', 'AI_GENERATE', 'AI_QA', NULL, 'ANY', TRUE, 110, 'CASE_AUTO'),
    ('DEV_CODING', '开发编码', 'BUILD', 'AI_GENERATE', 'AI_DEV', NULL, 'ANY', TRUE, 120, 'DEV_REPORT'),
    ('INT_TEST', '集成测试', 'BUILD', 'AI_GENERATE', 'AI_DEV', NULL, 'ANY', TRUE, 130, 'TEST_REPORT'),
    ('CONFIRM_INT_TEST', '集成测试确认', 'BUILD', 'HUMAN_APPROVE', NULL, 'DEV', 'ANY', FALSE, 140, NULL),
    ('PIPELINE_DONE', '流水线结束', 'BUILD', 'SYSTEM', NULL, NULL, 'ANY', TRUE, 150, NULL)
) AS s(code, name, stage, executor, role_pack, human_role, policy, auto_adv, sort_order, out_kind)
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL;

INSERT INTO pipe_transition (definition_id, from_step_code, to_step_code, event, priority)
SELECT d.id, t.frm, t.tto, t.evt, 0
FROM pipe_definition d
CROSS JOIN (VALUES
    ('PM_FIRST_APPROVE', 'AI_PRE_REVIEW', 'APPROVE'),
    ('AI_PRE_REVIEW', 'PM_PRE_REVIEW_CONFIRM', 'SUCCESS'),
    ('PM_PRE_REVIEW_CONFIRM', 'PM_SECOND_APPROVE', 'APPROVE'),
    ('PM_SECOND_APPROVE', 'GEN_PRD_V1', 'APPROVE'),
    ('GEN_PRD_V1', 'REVIEW_PRD_ARCH', 'SUCCESS'),
    ('REVIEW_PRD_ARCH', 'CONFIRM_PRD_ARCH', 'SUCCESS'),
    ('CONFIRM_PRD_ARCH', 'GEN_ARCH_V1', 'APPROVE'),
    ('GEN_ARCH_V1', 'GEN_UI_V1', 'SUCCESS'),
    ('GEN_UI_V1', 'GEN_DETAIL_V1', 'SUCCESS'),
    ('GEN_DETAIL_V1', 'GEN_CASES_V1', 'SUCCESS'),
    ('GEN_CASES_V1', 'DEV_CODING', 'SUCCESS'),
    ('DEV_CODING', 'INT_TEST', 'SUCCESS'),
    ('INT_TEST', 'CONFIRM_INT_TEST', 'SUCCESS'),
    ('CONFIRM_INT_TEST', 'PIPELINE_DONE', 'APPROVE')
) AS t(frm, tto, evt)
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL;
