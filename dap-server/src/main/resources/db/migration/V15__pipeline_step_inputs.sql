-- 补全 RD_AUTO 步骤输入产物种类（编排按 kind 取当前 Wiki）

UPDATE pipe_step_def s
SET input_artifact_kinds = v.kinds
FROM pipe_definition d,
     (VALUES
          ('AI_PRE_REVIEW', '["RAW_REQ"]'),
          ('GEN_PRD_V1', '["RAW_REQ"]'),
          ('REVIEW_PRD_ARCH', '["PRD"]'),
          ('GEN_ARCH_V1', '["PRD"]'),
          ('GEN_UI_V1', '["PRD"]'),
          ('GEN_DETAIL_V1', '["PRD","ARCH"]'),
          ('GEN_CASES_V1', '["PRD","ARCH"]'),
          ('DEV_CODING', '["DETAIL","PRD","ARCH"]'),
          ('INT_TEST', '["DEV_REPORT","CASE_AUTO"]')
     ) AS v(code, kinds)
WHERE d.id = s.definition_id
  AND d.code = 'RD_AUTO'
  AND d.version = 1
  AND d.tenant_id IS NULL
  AND s.code = v.code;
