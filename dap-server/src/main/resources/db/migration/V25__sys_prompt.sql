-- 租户提示词管理

CREATE TABLE sys_prompt (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL DEFAULT '',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_prompt_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_sys_prompt_tenant ON sys_prompt (tenant_id);

COMMENT ON TABLE sys_prompt IS '租户提示词（Markdown）';
COMMENT ON COLUMN sys_prompt.code IS '提示词编码，租户内唯一';
COMMENT ON COLUMN sys_prompt.content IS '提示词正文，Markdown';

INSERT INTO sys_permission (code, name, module, description) VALUES
('prompt:view', '提示词查看', '租户配置', '查看提示词列表与内容'),
('prompt:operate', '提示词操作', '租户配置', '提示词增删改');
