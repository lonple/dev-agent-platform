-- 移除自动化流水线运行时与定义（历史 V14–V19 种子一并清理）

ALTER TABLE wb_chat_session DROP CONSTRAINT IF EXISTS fk_wcs_output_wiki_dir;
DROP INDEX IF EXISTS idx_wcs_output_wiki_dir;
ALTER TABLE wb_chat_session DROP COLUMN IF EXISTS output_wiki_dir_node_id;
ALTER TABLE wb_chat_session DROP COLUMN IF EXISTS pipe_step_instance_id;

-- 若仍残留任务表上的流水线列（多数环境已在 V24 删表）
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'req_requirement_task'
    ) THEN
        ALTER TABLE req_requirement_task DROP COLUMN IF EXISTS pipe_step_instance_id;
        ALTER TABLE req_requirement_task DROP COLUMN IF EXISTS source;
    END IF;
END $$;

DROP TABLE IF EXISTS pipe_job CASCADE;
DROP TABLE IF EXISTS pipe_review_item CASCADE;
DROP TABLE IF EXISTS pipe_review CASCADE;
DROP TABLE IF EXISTS pipe_artifact CASCADE;
DROP TABLE IF EXISTS pipe_step_assignee CASCADE;
DROP TABLE IF EXISTS pipe_step_instance CASCADE;
DROP TABLE IF EXISTS pipe_instance CASCADE;
DROP TABLE IF EXISTS pipe_transition CASCADE;
DROP TABLE IF EXISTS pipe_step_def CASCADE;
DROP TABLE IF EXISTS pipe_definition CASCADE;
DROP TABLE IF EXISTS pipe_role_pack CASCADE;
DROP TABLE IF EXISTS pipe_step_assignee_def CASCADE;
