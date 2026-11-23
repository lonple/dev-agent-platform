-- 问题单状态扩展：OPEN=未处理 IN_PROGRESS=处理中 DONE=完成（存量 DONE 文案由枚举提供）
COMMENT ON COLUMN iss_issue.status IS 'OPEN=未处理 IN_PROGRESS=处理中 DONE=完成';
