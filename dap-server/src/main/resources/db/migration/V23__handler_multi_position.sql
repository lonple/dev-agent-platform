-- 同一用户可挂多个岗位（角色）；按 (需求, 用户, 岗位) 唯一
TRUNCATE TABLE req_requirement_handler RESTART IDENTITY;

ALTER TABLE req_requirement_handler
    DROP CONSTRAINT IF EXISTS uk_req_handler;

-- 有岗位：需求+用户+岗位 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_req_handler_user_pos
    ON req_requirement_handler (requirement_id, user_id, position_id)
    WHERE position_id IS NOT NULL;

-- 无岗位（投入研发默认负责人）：同一需求同一用户仅一条
CREATE UNIQUE INDEX IF NOT EXISTS uk_req_handler_user_owner
    ON req_requirement_handler (requirement_id, user_id)
    WHERE position_id IS NULL;
