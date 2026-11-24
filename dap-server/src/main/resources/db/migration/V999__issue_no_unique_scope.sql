-- DAP-0 集成测试修复：需求/问题编号唯一性作用域与编号序列统一为「租户内唯一」
-- 背景：iss_issue_no_seq 按 (tenant_id, product_id) 自增，而 iss_issue.issue_no 建的是全局唯一约束
--       （CONSTRAINT uk_iss_issue_no UNIQUE (issue_no)）。不同租户使用相同产品编码属于合法场景，
--       此时两个租户会生成相同的 issue_no -> DuplicateKey -> POST /api/issues 500。
-- 修复：编号契约统一为 (tenant_id, <编号列>) 唯一，不再全局唯一。
-- 本脚本幂等，可重复执行（跨租户与重复执行均安全）。

-- 1) 问题单：删掉全局唯一约束，重建为租户内唯一
ALTER TABLE iss_issue DROP CONSTRAINT IF EXISTS uk_iss_issue_no;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_iss_issue_tenant_no'
          AND conrelid = 'iss_issue'::regclass
    ) THEN
        ALTER TABLE iss_issue
            ADD CONSTRAINT uk_iss_issue_tenant_no UNIQUE (tenant_id, issue_no);
    END IF;
END $$;

-- 2) 其余带 issue_no / requirement_no 的业务表：把「只有该列」的全局唯一约束
--    同样收敛为 (tenant_id, 编号列)，保证编号契约在所有编号表上作用域一致。
DO $$
DECLARE
    r RECORD;
    new_constraint_name TEXT;
BEGIN
    FOR r IN
        SELECT c.conname        AS conname,
               t.relname        AS tbl,
               a.attname        AS col
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN LATERAL unnest(c.conkey) AS k(attnum) ON TRUE
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
        WHERE c.contype = 'u'
          AND n.nspname = current_schema()
          AND a.attname IN ('issue_no', 'requirement_no')
          AND array_length(c.conkey, 1) = 1
          AND EXISTS (
              SELECT 1
              FROM pg_attribute ta
              WHERE ta.attrelid = c.conrelid
                AND ta.attname = 'tenant_id'
                AND NOT ta.attisdropped
          )
    LOOP
        new_constraint_name := 'uk_' || r.tbl || '_tenant_no';
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', r.tbl, r.conname);
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = new_constraint_name
              AND conrelid = r.tbl::regclass
        ) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_id, %I)',
                           r.tbl, new_constraint_name, r.col);
        END IF;
    END LOOP;
END $$;

COMMENT ON CONSTRAINT uk_iss_issue_tenant_no ON iss_issue IS '问题单编码租户内唯一（与编号序列作用域一致）';
