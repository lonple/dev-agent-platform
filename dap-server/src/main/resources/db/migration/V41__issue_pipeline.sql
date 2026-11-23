-- 问题单可选关联需求
ALTER TABLE iss_issue
    ADD COLUMN IF NOT EXISTS requirement_id BIGINT;

ALTER TABLE iss_issue
    DROP CONSTRAINT IF EXISTS fk_iss_requirement;
ALTER TABLE iss_issue
    ADD CONSTRAINT fk_iss_requirement
        FOREIGN KEY (requirement_id) REFERENCES req_requirement (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_iss_issue_requirement ON iss_issue (requirement_id);

COMMENT ON COLUMN iss_issue.requirement_id IS '可选关联需求，仅作 Wiki 上下文增强';

-- 流水实例支持问题主体（与需求二选一）
ALTER TABLE pipe_instance
    ALTER COLUMN requirement_id DROP NOT NULL;

ALTER TABLE pipe_instance
    ADD COLUMN IF NOT EXISTS issue_id BIGINT;

ALTER TABLE pipe_instance
    DROP CONSTRAINT IF EXISTS fk_pipe_inst_issue;
ALTER TABLE pipe_instance
    ADD CONSTRAINT fk_pipe_inst_issue
        FOREIGN KEY (issue_id) REFERENCES iss_issue (id) ON DELETE CASCADE;

ALTER TABLE pipe_instance
    DROP CONSTRAINT IF EXISTS ck_pipe_inst_subject;
ALTER TABLE pipe_instance
    ADD CONSTRAINT ck_pipe_inst_subject CHECK (
        (requirement_id IS NOT NULL AND issue_id IS NULL)
            OR (requirement_id IS NULL AND issue_id IS NOT NULL)
        );

CREATE UNIQUE INDEX IF NOT EXISTS uk_pipe_instance_issue_active
    ON pipe_instance (issue_id)
    WHERE issue_id IS NOT NULL
      AND status IN ('RUNNING', 'WAITING_HUMAN', 'SUSPENDED', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_pipe_instance_issue ON pipe_instance (issue_id);

-- worktree / artifact 支持 issue
ALTER TABLE pipe_repo_worktree
    ALTER COLUMN requirement_id DROP NOT NULL;

ALTER TABLE pipe_repo_worktree
    ADD COLUMN IF NOT EXISTS issue_id BIGINT;

ALTER TABLE pipe_repo_worktree
    DROP CONSTRAINT IF EXISTS fk_pipe_wt_issue;
ALTER TABLE pipe_repo_worktree
    ADD CONSTRAINT fk_pipe_wt_issue
        FOREIGN KEY (issue_id) REFERENCES iss_issue (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_pipe_repo_wt_issue ON pipe_repo_worktree (issue_id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'pipe_artifact'
    ) THEN
        EXECUTE 'ALTER TABLE pipe_artifact ALTER COLUMN requirement_id DROP NOT NULL';
        EXECUTE 'ALTER TABLE pipe_artifact ADD COLUMN IF NOT EXISTS issue_id BIGINT';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_pipe_artifact_issue ON pipe_artifact (issue_id)';
    END IF;
END $$;

-- 平台种子：问题单短流水 ISSUE_AUTO v1
INSERT INTO pipe_definition (code, version, name, tenant_id, enabled)
SELECT 'ISSUE_AUTO', 1, '问题单自动化流水线', NULL, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM pipe_definition
    WHERE code = 'ISSUE_AUTO' AND version = 1 AND tenant_id IS NULL
);

INSERT INTO pipe_node_def (definition_id, code, name, kind, optional_flag, parallel_group, join_group, sort_order)
SELECT d.id, n.code, n.name, n.kind, n.opt, n.pg, n.jg, n.so
FROM pipe_definition d
CROSS JOIN (VALUES
    ('issue_analyze',  '问题分析',     'AI',     FALSE, NULL, NULL, 10),
    ('issue_fix',      '问题修复',     'AI',     FALSE, NULL, NULL, 20),
    ('issue_int_test', '存量集成测试', 'AI',     FALSE, NULL, NULL, 30),
    ('done',           '流水线结束',   'SYSTEM', FALSE, NULL, NULL, 40)
) AS n(code, name, kind, opt, pg, jg, so)
WHERE d.code = 'ISSUE_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM pipe_node_def nd
      WHERE nd.definition_id = d.id AND nd.code = n.code
  );

INSERT INTO pipe_unit_def (node_def_id, code, name, position_code, task_type_code, round_tier, max_round, repo_kind, output_kind, sort_order)
SELECT nd.id, u.code, u.name, u.pos, u.tt, u.tier, u.mr, u.rk, u.ok, u.so
FROM pipe_node_def nd
JOIN pipe_definition d ON d.id = nd.definition_id
CROSS JOIN LATERAL (
    SELECT * FROM (VALUES
        ('issue_analyze',  'analyze',  '问题定位分析', 'DEV', 'GEN_ISSUE_ANALYSIS', 'T48', 48, 'NONE', 'ISSUE_ANALYSIS', 1),
        ('issue_fix',      'fix',     '问题修复编码', 'DEV', 'GEN_ISSUE_FIX',      'T96', 96, 'BIZ',  'ISSUE_FIX', 1),
        ('issue_int_test', 'int_test', '存量集成测试', 'QA',  'GEN_ISSUE_INT_TEST',  'T96', 96, 'TEST', 'ISSUE_INT_TEST', 1)
    ) AS x(node_code, code, name, pos, tt, tier, mr, rk, ok, so)
    WHERE x.node_code = nd.code
) AS u
WHERE d.code = 'ISSUE_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM pipe_unit_def ud
      WHERE ud.node_def_id = nd.id AND ud.code = u.code
  );

INSERT INTO pipe_transition (definition_id, from_node_code, to_node_code, event, priority, condition_json)
SELECT d.id, t.frm, t.tto, t.evt, t.pri, t.cond
FROM pipe_definition d
CROSS JOIN (VALUES
    ('issue_analyze',  'issue_fix',      'SUCCESS', 0, NULL),
    ('issue_fix',      'issue_int_test', 'SUCCESS', 0, NULL),
    ('issue_int_test', 'done',           'SUCCESS', 0, NULL)
) AS t(frm, tto, evt, pri, cond)
WHERE d.code = 'ISSUE_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM pipe_transition tr
      WHERE tr.definition_id = d.id
        AND tr.from_node_code = t.frm
        AND tr.to_node_code = t.tto
        AND tr.event = t.evt
  );
