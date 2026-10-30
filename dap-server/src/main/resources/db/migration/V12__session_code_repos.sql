-- 任务会话（编码 / 自动化测试等需仓内白名单工具的类型）绑定的本地代码仓库路径，可多条

CREATE TABLE IF NOT EXISTS wb_session_code_repo (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    local_path      VARCHAR(1000) NOT NULL,
    sort_no         INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wscr_session FOREIGN KEY (session_id) REFERENCES wb_chat_session (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wscr_session ON wb_session_code_repo (session_id, sort_no);
