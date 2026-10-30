-- 需求处理人角色改为租户岗位（sys_position）；不做历史兼容
TRUNCATE TABLE req_requirement_handler RESTART IDENTITY;

ALTER TABLE req_requirement_handler DROP COLUMN IF EXISTS role_code;

ALTER TABLE req_requirement_handler
    ADD COLUMN IF NOT EXISTS position_id BIGINT;

ALTER TABLE req_requirement_handler
    DROP CONSTRAINT IF EXISTS fk_req_handler_position;

ALTER TABLE req_requirement_handler
    ADD CONSTRAINT fk_req_handler_position
        FOREIGN KEY (position_id) REFERENCES sys_position (id) ON DELETE SET NULL;

COMMENT ON COLUMN req_requirement_handler.position_id IS '处理人角色=租户岗位；投入研发默认负责人可为空';
