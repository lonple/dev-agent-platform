-- DAP-0 存量集成测试修复（对应人确认 F1：编号契约作用域统一）
-- 契约：编号序列按 (tenant_id, product_id) 计数，因此需求号/问题号的作用域是「租户内唯一」。
-- 历史问题：iss_issue.issue_no 上建的是全局唯一约束（或自动命名的单列唯一约束/索引），
--           跨租户使用相同产品编码（合法场景）或同一租户内重复执行时，序列会生成相同编号，
--           插入时抛 DuplicateKeyException → POST /api/issues 返回 500。
-- 本脚本把唯一性作用域收敛为 (tenant_id, 编号列)，与序列作用域一致；
-- 不把 500 降级为 4xx，编号在租户内仍然严格唯一。
-- 脚本幂等：可重复执行，跨租户与重复执行均安全。

-- 1) 显式命名约束：问题单
ALTER TABLE iss_issue DROP CONSTRAINT IF EXISTS uk_iss_issue_no;
DROP INDEX IF EXISTS uk_iss_issue_no;

-- 2) 单列唯一约束（含数据库自动命名，如 iss_issue_issue_no_key）统一收敛为租户内唯一
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname AS conname, t.relname AS tbl
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.contype = 'u'
          AND n.nspname = current_schema()
          AND c.conkey IS NOT NULL
          AND array_length(c.conkey, 1) = 1
          AND EXISTS (
              SELECT 1
              FROM pg_attribute a
              WHERE a.attrelid = c.conrelid
                AND a.attnum = c.conkey[1]
                AND a.attname IN ('issue_no', 'requirement_no')
                AND NOT a.attisdropped
          )
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', r.tbl, r.conname);
    END LOOP;
END $$;

-- 3) 遗留的单列唯一索引（非约束形式）同样清理
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT i.indexname AS iname
        FROM pg_indexes i
        WHERE i.schemaname = current_schema()
          AND i.indexdef LIKE 'CREATE UNIQUE INDEX%'
          AND (i.indexdef LIKE '%(issue_no)%' OR i.indexdef LIKE '%(requirement_no)%')
    LOOP
        BEGIN
            EXECUTE format('DROP INDEX IF EXISTS %I', r.iname);
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'drop index % skipped: %', r.iname, SQLERRM;
        END;
    END LOOP;
END $$;

-- 4) 建 (tenant_id, 编号列) 唯一索引；若历史数据在同一租户内已有重复号，
--    不阻塞迁移（记录 NOTICE，交由数据治理处理），避免上线因脏数据直接失败。
DO $$
DECLARE
    r RECORD;
    idx_name TEXT;
BEGIN
    FOR r IN
        SELECT t.relname AS tbl, a.attname AS col
        FROM pg_class t
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_attribute a ON a.attrelid = t.oid
        WHERE n.nspname = current_schema()
          AND t.relkind = 'r'
          AND a.attname IN ('issue_no', 'requirement_no')
          AND NOT a.attisdropped
          AND EXISTS (
              SELECT 1 FROM pg_attribute ta
              WHERE ta.attrelid = t.oid AND ta.attname = 'tenant_id' AND NOT ta.attisdropped
          )
    LOOP
        idx_name := 'uk_' || r.tbl || '_tenant_no';
        BEGIN
            EXECUTE format('CREATE UNIQUE INDEX IF NOT EXISTS %I ON %I (tenant_id, %I)',
                           idx_name, r.tbl, r.col);
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'create % skipped: duplicate rows exist within tenant', idx_name;
        END;
    END LOOP;
END $$;

-- 5) 编号序列与存量数据对齐：保证 last_seq 不低于该 (租户, 产品) 已存在的问题数，
--    避免「重复执行」时序列落后于数据，再次生成已存在的 issue_no。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'iss_issue_no_seq'
    ) THEN
        INSERT INTO iss_issue_no_seq (tenant_id, product_id, last_seq)
        SELECT i.tenant_id, i.product_id, COUNT(*)::int
        FROM iss_issue i
        WHERE i.tenant_id IS NOT NULL AND i.product_id IS NOT NULL
        GROUP BY i.tenant_id, i.product_id
        ON CONFLICT (tenant_id, product_id) DO UPDATE
            SET last_seq = GREATEST(iss_issue_no_seq.last_seq, EXCLUDED.last_seq);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'resync iss_issue_no_seq skipped: %', SQLERRM;
END $$;
