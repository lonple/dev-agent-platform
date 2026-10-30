-- 需求处理人：仅「投入研发」后使用；不做历史兼容，清空旧「相关人」语义数据
TRUNCATE TABLE req_requirement_handler RESTART IDENTITY;

COMMENT ON TABLE req_requirement_handler IS '需求处理人（DEVELOPING 后多人协作；可看需求、自建任务）';
