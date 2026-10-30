-- 任务会话可绑定 Wiki 知识库中的「输出参考模板」文件
ALTER TABLE wb_chat_session
    ADD COLUMN IF NOT EXISTS output_template_wiki_node_id BIGINT;

ALTER TABLE wb_chat_session
    DROP CONSTRAINT IF EXISTS fk_wcs_output_template;

ALTER TABLE wb_chat_session
    ADD CONSTRAINT fk_wcs_output_template
        FOREIGN KEY (output_template_wiki_node_id) REFERENCES sys_wiki_node (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_wcs_output_template
    ON wb_chat_session (output_template_wiki_node_id);
