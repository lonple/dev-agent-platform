-- 需求处理人增加角色（不做历史兼容）
TRUNCATE TABLE req_requirement_handler RESTART IDENTITY;

ALTER TABLE req_requirement_handler
    ADD COLUMN IF NOT EXISTS role_code VARCHAR(32) NOT NULL DEFAULT 'OWNER';

COMMENT ON COLUMN req_requirement_handler.role_code IS '处理人角色：OWNER/PM/ARCH/DEV/QA/OTHER';
