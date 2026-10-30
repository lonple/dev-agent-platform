-- 不做历史兼容：清空工作空间及相关数据后调整结构
DELETE FROM req_requirement_task_input;
DELETE FROM sys_workspace;

ALTER TABLE sys_workspace DROP COLUMN IF EXISTS description;

ALTER TABLE sys_workspace DROP CONSTRAINT IF EXISTS uk_sys_workspace_tenant_dir;

-- 共享：租户内 work_dir 唯一；个人：同一所有者下 work_dir 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_workspace_shared_dir
    ON sys_workspace (tenant_id, work_dir)
    WHERE space_type = 'SHARED';

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_workspace_personal_dir
    ON sys_workspace (tenant_id, owner_user_id, work_dir)
    WHERE space_type = 'PERSONAL';
