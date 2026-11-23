-- 个人空间节点可隐藏：流水线确认归档草稿在「完成输出」前不出现在目录树
ALTER TABLE sys_workspace_node
    ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sys_workspace_node.hidden IS 'true=目录树不展示（磁盘与节点仍存在）';

CREATE INDEX IF NOT EXISTS idx_wsn_hidden
    ON sys_workspace_node (workspace_id, hidden)
    WHERE hidden = TRUE;

ALTER TABLE pipe_unit_instance
    ADD COLUMN IF NOT EXISTS output_workspace_node_id BIGINT;

COMMENT ON COLUMN pipe_unit_instance.output_workspace_node_id IS '正式产出在个人空间的文件节点；发布 Wiki 前可读';
COMMENT ON COLUMN pipe_unit_instance.output_wiki_node_id IS '正式产出发布到 Wiki 后的节点；手动发布后写入';
