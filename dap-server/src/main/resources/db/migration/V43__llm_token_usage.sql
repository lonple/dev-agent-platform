-- 会话关联问题单（问题流水 TASK 会话）
ALTER TABLE wb_chat_session
    ADD COLUMN IF NOT EXISTS issue_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_wb_chat_session_issue ON wb_chat_session (issue_id);

COMMENT ON COLUMN wb_chat_session.issue_id IS '任务会话关联问题单（与需求可并存：问题可挂关联需求）';

-- LLM Token 用量（按 run 累计落库，供运维统计）
CREATE TABLE IF NOT EXISTS llm_token_usage (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    session_id           BIGINT,
    message_id           BIGINT,
    run_id               VARCHAR(64)  NOT NULL,
    requirement_id       BIGINT,
    issue_id             BIGINT,
    task_type_code       VARCHAR(64),
    prompt_tokens        BIGINT       NOT NULL DEFAULT 0,
    completion_tokens    BIGINT       NOT NULL DEFAULT 0,
    total_tokens         BIGINT       NOT NULL DEFAULT 0,
    occurred_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_llm_token_usage_run UNIQUE (tenant_id, run_id)
);

CREATE INDEX IF NOT EXISTS idx_llm_token_usage_tenant_user ON llm_token_usage (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_llm_token_usage_req ON llm_token_usage (tenant_id, requirement_id);
CREATE INDEX IF NOT EXISTS idx_llm_token_usage_issue ON llm_token_usage (tenant_id, issue_id);

COMMENT ON TABLE llm_token_usage IS 'LLM token 用量：每次 Agent 响应(run)一行，数值为该 run 累计值';
