-- 任务会话可绑定 Wiki「规范」目录（DIR）
ALTER TABLE wb_chat_session
    ADD COLUMN IF NOT EXISTS standards_wiki_node_id BIGINT;

COMMENT ON COLUMN wb_chat_session.standards_wiki_node_id IS '规范：Wiki「规范」目录下的 DIR 节点';

ALTER TABLE wb_chat_session
    DROP CONSTRAINT IF EXISTS fk_wcs_standards_wiki;

ALTER TABLE wb_chat_session
    ADD CONSTRAINT fk_wcs_standards_wiki
        FOREIGN KEY (standards_wiki_node_id) REFERENCES sys_wiki_node (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_wcs_standards_wiki
    ON wb_chat_session (standards_wiki_node_id);
