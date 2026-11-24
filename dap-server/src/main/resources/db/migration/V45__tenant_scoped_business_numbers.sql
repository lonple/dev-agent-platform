-- 产品编码与编号序列均按租户隔离，业务编号也必须在租户内唯一。
-- 只替换这两个业务表的单列唯一约束；保留数据与序列，失败时由 Flyway 事务回滚。
ALTER TABLE iss_issue DROP CONSTRAINT IF EXISTS uk_iss_issue_no;
DROP INDEX IF EXISTS uk_iss_issue_no;
ALTER TABLE iss_issue
    ADD CONSTRAINT uk_iss_issue_tenant_no UNIQUE (tenant_id, issue_no);

ALTER TABLE req_requirement DROP CONSTRAINT IF EXISTS uk_req_requirement_no;
DROP INDEX IF EXISTS uk_req_requirement_no;
ALTER TABLE req_requirement
    ADD CONSTRAINT uk_req_requirement_tenant_no UNIQUE (tenant_id, req_no);
