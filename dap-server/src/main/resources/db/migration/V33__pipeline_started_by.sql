ALTER TABLE pipe_instance
    ADD COLUMN IF NOT EXISTS started_by BIGINT;

COMMENT ON COLUMN pipe_instance.started_by IS '启动人用户 ID，Job Worker 还原租户上下文用';
