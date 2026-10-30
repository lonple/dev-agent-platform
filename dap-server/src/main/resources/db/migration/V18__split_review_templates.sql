-- 评审书按场景拆分后，绑定到对应 AI 评审步骤的输出模板

UPDATE pipe_step_def s
SET template_wiki_path = v.path
FROM pipe_definition d,
     (VALUES
          ('AI_PRE_REVIEW', '流水线模板/模板-评审-原始需求初审.md'),
          ('REVIEW_PRD_ARCH', '流水线模板/模板-评审-PRD架构师初审.md')
     ) AS v(code, path)
WHERE d.id = s.definition_id
  AND d.code = 'RD_AUTO'
  AND d.version = 1
  AND d.tenant_id IS NULL
  AND s.code = v.code;
