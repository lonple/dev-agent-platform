-- 会话改用枚举任务类型 + 岗位；删除需求任务 / 任务类型主数据（不兼容历史）

-- 先清会话对任务的外键
ALTER TABLE wb_chat_session DROP CONSTRAINT IF EXISTS fk_wcs_task;
UPDATE wb_chat_session SET task_id = NULL WHERE task_id IS NOT NULL;

ALTER TABLE wb_chat_session DROP COLUMN IF EXISTS task_id;
ALTER TABLE wb_chat_session ADD COLUMN IF NOT EXISTS task_type_code VARCHAR(64);
COMMENT ON COLUMN wb_chat_session.task_type_code IS '任务会话类型枚举 SessionTaskType.code';

-- 流水线步骤不再挂 req 任务
ALTER TABLE pipe_step_instance DROP COLUMN IF EXISTS task_id;

-- 岗位增加稳定编码，供任务类型默认岗位匹配
ALTER TABLE sys_position ADD COLUMN IF NOT EXISTS code VARCHAR(32);
COMMENT ON COLUMN sys_position.code IS '岗位编码：PM/ARCH/DEV/QA 等';
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_position_tenant_code
    ON sys_position (tenant_id, code) WHERE code IS NOT NULL AND code <> '';

-- 按名称启发式回填岗位编码（仅空编码）
UPDATE sys_position SET code = 'PM'
 WHERE (code IS NULL OR code = '')
   AND (name ILIKE '%产品%' OR name ILIKE '%需求%' OR name ILIKE '%PM%' OR name ILIKE '%产品经理%');
UPDATE sys_position SET code = 'ARCH'
 WHERE (code IS NULL OR code = '')
   AND (name ILIKE '%架构%' OR name ILIKE '%ARCH%');
UPDATE sys_position SET code = 'DEV'
 WHERE (code IS NULL OR code = '')
   AND (name ILIKE '%研发%' OR name ILIKE '%开发%' OR name ILIKE '%DEV%' OR name ILIKE '%工程师%');
UPDATE sys_position SET code = 'QA'
 WHERE (code IS NULL OR code = '')
   AND (name ILIKE '%测试%' OR name ILIKE '%QA%' OR name ILIKE '%质量%');

-- 删除需求任务体系
DROP TABLE IF EXISTS req_requirement_task_input CASCADE;
DROP TABLE IF EXISTS req_requirement_task_handler CASCADE;
DROP TABLE IF EXISTS req_requirement_task CASCADE;
DROP TABLE IF EXISTS sys_req_task_type CASCADE;

-- 清理权限
DELETE FROM sys_role_permission
 WHERE permission_id IN (SELECT id FROM sys_permission WHERE code LIKE 'req-task-type:%');
DELETE FROM sys_permission WHERE code LIKE 'req-task-type:%';
