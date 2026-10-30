-- 删除工作空间文件/目录时，同步清理任务输入交付件引用
ALTER TABLE req_requirement_task_input DROP CONSTRAINT IF EXISTS fk_rrti_node;
ALTER TABLE req_requirement_task_input
    ADD CONSTRAINT fk_rrti_node
        FOREIGN KEY (node_id) REFERENCES sys_workspace_node (id) ON DELETE CASCADE;

ALTER TABLE req_requirement_task_input DROP CONSTRAINT IF EXISTS fk_rrti_ws;
ALTER TABLE req_requirement_task_input
    ADD CONSTRAINT fk_rrti_ws
        FOREIGN KEY (workspace_id) REFERENCES sys_workspace (id) ON DELETE CASCADE;
