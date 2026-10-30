-- 提示词绑定岗位 + 内置任务类型（供工作台任务会话按组合加载）

ALTER TABLE sys_prompt ADD COLUMN IF NOT EXISTS position_id BIGINT;
ALTER TABLE sys_prompt ADD COLUMN IF NOT EXISTS task_type_code VARCHAR(64);

COMMENT ON COLUMN sys_prompt.position_id IS '绑定岗位 sys_position.id';
COMMENT ON COLUMN sys_prompt.task_type_code IS '绑定任务类型 SessionTaskType.name';

-- 历史数据无绑定的可先删空壳，再强制非空
DELETE FROM sys_prompt WHERE position_id IS NULL OR task_type_code IS NULL OR task_type_code = '';

ALTER TABLE sys_prompt ALTER COLUMN position_id SET NOT NULL;
ALTER TABLE sys_prompt ALTER COLUMN task_type_code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_prompt_tenant_pos_task
    ON sys_prompt (tenant_id, position_id, task_type_code);

CREATE INDEX IF NOT EXISTS idx_sys_prompt_position ON sys_prompt (position_id);
CREATE INDEX IF NOT EXISTS idx_sys_prompt_task_type ON sys_prompt (tenant_id, task_type_code);
