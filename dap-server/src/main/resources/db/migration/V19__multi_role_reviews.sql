-- 多角色评审步骤 + 共用模板路径绑定（RD_AUTO v1）
-- 架构/需求/详设/用例：两路 AI_REVIEW 后进人闸合并；UI：单路 AI_PM

-- 1) 插入多角色评审与确认步（若不存在）
INSERT INTO pipe_step_def (definition_id, code, name, stage, executor, role_pack_code, human_role, assignee_policy, auto_advance, sort_order, output_artifact_kind)
SELECT d.id, v.code, v.name, v.stage, v.executor, v.role_pack, v.human_role, v.policy, v.auto_adv, v.sort_order, v.out_kind
FROM pipe_definition d
CROSS JOIN (VALUES
    ('REVIEW_PRD_DEV', '需求分析评审(研发)', 'DESIGN', 'AI_REVIEW', 'AI_DEV', NULL, 'ANY', TRUE, 82, 'PRD_REVIEW_DEV'),
    ('REVIEW_PRD_QA', '需求分析评审(测试)', 'DESIGN', 'AI_REVIEW', 'AI_QA', NULL, 'ANY', TRUE, 84, 'PRD_REVIEW_QA'),
    ('CONFIRM_PRD_REV', '需求分析评审确认', 'DESIGN', 'HUMAN_APPROVE', NULL, 'DEV', 'ANY', FALSE, 86, NULL),
    ('REVIEW_ARCH_DEV', '架构设计评审(研发)', 'DESIGN', 'AI_REVIEW', 'AI_DEV', NULL, 'ANY', TRUE, 92, 'ARCH_REVIEW_DEV'),
    ('REVIEW_ARCH_QA', '架构设计评审(测试)', 'DESIGN', 'AI_REVIEW', 'AI_QA', NULL, 'ANY', TRUE, 94, 'ARCH_REVIEW_QA'),
    ('CONFIRM_ARCH', '架构设计评审确认', 'DESIGN', 'HUMAN_APPROVE', NULL, 'ARCH', 'ANY', FALSE, 96, NULL),
    ('REVIEW_UI_PM', 'UI评审(产品)', 'DESIGN', 'AI_REVIEW', 'AI_PM', NULL, 'ANY', TRUE, 102, 'UI_REVIEW'),
    ('CONFIRM_UI', 'UI评审确认', 'DESIGN', 'HUMAN_APPROVE', NULL, 'PM', 'ANY', FALSE, 104, NULL),
    ('REVIEW_DETAIL_QA', '详细设计评审(测试)', 'DETAIL', 'AI_REVIEW', 'AI_QA', NULL, 'ANY', TRUE, 112, 'DETAIL_REVIEW_QA'),
    ('REVIEW_DETAIL_ARCH', '详细设计评审(架构)', 'DETAIL', 'AI_REVIEW', 'AI_ARCH', NULL, 'ANY', TRUE, 114, 'DETAIL_REVIEW_ARCH'),
    ('CONFIRM_DETAIL', '详细设计评审确认', 'DETAIL', 'HUMAN_APPROVE', NULL, 'ARCH', 'ANY', FALSE, 116, NULL),
    ('REVIEW_CASE_DEV', '测试用例评审(研发)', 'DETAIL', 'AI_REVIEW', 'AI_DEV', NULL, 'ANY', TRUE, 122, 'CASE_REVIEW_DEV'),
    ('REVIEW_CASE_ARCH', '测试用例评审(架构)', 'DETAIL', 'AI_REVIEW', 'AI_ARCH', NULL, 'ANY', TRUE, 124, 'CASE_REVIEW_ARCH'),
    ('CONFIRM_CASE', '测试用例评审确认', 'DETAIL', 'HUMAN_APPROVE', NULL, 'DEV', 'ANY', FALSE, 126, NULL)
) AS v(code, name, stage, executor, role_pack, human_role, policy, auto_adv, sort_order, out_kind)
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM pipe_step_def s
      WHERE s.definition_id = d.id AND s.code = v.code
  );

-- 2) 调整既有步骤排序，腾出区间
UPDATE pipe_step_def s
SET sort_order = v.sort_order
FROM pipe_definition d,
     (VALUES
          ('GEN_ARCH_V1', 80),
          ('GEN_UI_V1', 100),
          ('GEN_DETAIL_V1', 110),
          ('GEN_CASES_V1', 120),
          ('DEV_CODING', 130),
          ('INT_TEST', 140),
          ('CONFIRM_INT_TEST', 150),
          ('PIPELINE_DONE', 160)
     ) AS v(code, sort_order)
WHERE d.id = s.definition_id
  AND d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND s.code = v.code;

-- 3) 重接 transition：去掉旧直连，写入评审链
DELETE FROM pipe_transition t
USING pipe_definition d
WHERE t.definition_id = d.id
  AND d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND (
      (t.from_step_code = 'CONFIRM_PRD_ARCH' AND t.to_step_code = 'GEN_ARCH_V1')
      OR (t.from_step_code = 'GEN_ARCH_V1' AND t.to_step_code = 'GEN_UI_V1')
      OR (t.from_step_code = 'GEN_UI_V1' AND t.to_step_code = 'GEN_DETAIL_V1')
      OR (t.from_step_code = 'GEN_DETAIL_V1' AND t.to_step_code = 'GEN_CASES_V1')
      OR (t.from_step_code = 'GEN_CASES_V1' AND t.to_step_code = 'DEV_CODING')
  );

INSERT INTO pipe_transition (definition_id, from_step_code, to_step_code, event, priority)
SELECT d.id, t.frm, t.tto, t.evt, 0
FROM pipe_definition d
CROSS JOIN (VALUES
    ('CONFIRM_PRD_ARCH', 'GEN_ARCH_V1', 'APPROVE'),
    ('GEN_ARCH_V1', 'GEN_UI_V1', 'SUCCESS'),
    ('GEN_UI_V1', 'REVIEW_PRD_DEV', 'SUCCESS'),
    ('REVIEW_PRD_DEV', 'REVIEW_PRD_QA', 'SUCCESS'),
    ('REVIEW_PRD_QA', 'CONFIRM_PRD_REV', 'SUCCESS'),
    ('CONFIRM_PRD_REV', 'REVIEW_ARCH_DEV', 'APPROVE'),
    ('REVIEW_ARCH_DEV', 'REVIEW_ARCH_QA', 'SUCCESS'),
    ('REVIEW_ARCH_QA', 'CONFIRM_ARCH', 'SUCCESS'),
    ('CONFIRM_ARCH', 'REVIEW_UI_PM', 'APPROVE'),
    ('REVIEW_UI_PM', 'CONFIRM_UI', 'SUCCESS'),
    ('CONFIRM_UI', 'GEN_DETAIL_V1', 'APPROVE'),
    ('GEN_DETAIL_V1', 'REVIEW_DETAIL_QA', 'SUCCESS'),
    ('REVIEW_DETAIL_QA', 'REVIEW_DETAIL_ARCH', 'SUCCESS'),
    ('REVIEW_DETAIL_ARCH', 'CONFIRM_DETAIL', 'SUCCESS'),
    ('CONFIRM_DETAIL', 'GEN_CASES_V1', 'APPROVE'),
    ('GEN_CASES_V1', 'REVIEW_CASE_DEV', 'SUCCESS'),
    ('REVIEW_CASE_DEV', 'REVIEW_CASE_ARCH', 'SUCCESS'),
    ('REVIEW_CASE_ARCH', 'CONFIRM_CASE', 'SUCCESS'),
    ('CONFIRM_CASE', 'DEV_CODING', 'APPROVE')
) AS t(frm, tto, evt)
WHERE d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM pipe_transition x
      WHERE x.definition_id = d.id
        AND x.from_step_code = t.frm
        AND x.to_step_code = t.tto
        AND x.event = t.evt
  );

-- 4) 模板路径（共用模板按场景；多角色同 path）
UPDATE pipe_step_def s
SET template_wiki_path = v.path
FROM pipe_definition d,
     (VALUES
          ('AI_PRE_REVIEW', '流水线模板/模板-评审-原始需求初审.md'),
          ('REVIEW_PRD_ARCH', '流水线模板/模板-评审-PRD架构师初审.md'),
          ('REVIEW_PRD_DEV', '流水线模板/模板-评审-需求分析.md'),
          ('REVIEW_PRD_QA', '流水线模板/模板-评审-需求分析.md'),
          ('REVIEW_ARCH_DEV', '流水线模板/模板-评审-架构设计.md'),
          ('REVIEW_ARCH_QA', '流水线模板/模板-评审-架构设计.md'),
          ('REVIEW_UI_PM', '流水线模板/模板-评审-UI设计.md'),
          ('REVIEW_DETAIL_QA', '流水线模板/模板-评审-详细设计.md'),
          ('REVIEW_DETAIL_ARCH', '流水线模板/模板-评审-详细设计.md'),
          ('REVIEW_CASE_DEV', '流水线模板/模板-评审-测试用例.md'),
          ('REVIEW_CASE_ARCH', '流水线模板/模板-评审-测试用例.md'),
          ('INT_TEST', '流水线模板/模板-集成测试执行摘要.md')
     ) AS v(code, path)
WHERE d.id = s.definition_id
  AND d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND s.code = v.code;

-- 5) 输入产物
UPDATE pipe_step_def s
SET input_artifact_kinds = v.kinds
FROM pipe_definition d,
     (VALUES
          ('REVIEW_PRD_DEV', '["PRD"]'),
          ('REVIEW_PRD_QA', '["PRD"]'),
          ('REVIEW_ARCH_DEV', '["ARCH","PRD"]'),
          ('REVIEW_ARCH_QA', '["ARCH","PRD"]'),
          ('REVIEW_UI_PM', '["UI_SPEC","PRD"]'),
          ('REVIEW_DETAIL_QA', '["DETAIL","PRD","ARCH"]'),
          ('REVIEW_DETAIL_ARCH', '["DETAIL","PRD","ARCH"]'),
          ('REVIEW_CASE_DEV', '["CASE_AUTO","PRD","ARCH"]'),
          ('REVIEW_CASE_ARCH', '["CASE_AUTO","PRD","ARCH"]')
     ) AS v(code, kinds)
WHERE d.id = s.definition_id
  AND d.code = 'RD_AUTO' AND d.version = 1 AND d.tenant_id IS NULL
  AND s.code = v.code;
