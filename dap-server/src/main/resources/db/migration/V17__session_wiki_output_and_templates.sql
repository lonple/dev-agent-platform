-- 流水线任务会话：输出可落 Wiki 目录；步骤绑定模板相对路径

ALTER TABLE wb_chat_session
    ADD COLUMN IF NOT EXISTS output_wiki_dir_node_id BIGINT;

COMMENT ON COLUMN wb_chat_session.output_wiki_dir_node_id IS '流水线任务输出目录（Wiki DIR）；手动任务勿用，仅个人空间 output_dir_node_id';

ALTER TABLE wb_chat_session
    DROP CONSTRAINT IF EXISTS fk_wcs_output_wiki_dir;

ALTER TABLE wb_chat_session
    ADD CONSTRAINT fk_wcs_output_wiki_dir
        FOREIGN KEY (output_wiki_dir_node_id) REFERENCES sys_wiki_node (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_wcs_output_wiki_dir ON wb_chat_session (output_wiki_dir_node_id);

-- 步骤模板路径（相对 Wiki「流水线模板」目录下的文件名）
UPDATE pipe_step_def s
SET template_wiki_path = v.path
FROM pipe_definition d,
     (VALUES
          ('AI_PRE_REVIEW', '流水线模板/模板-评审书.md'),
          ('GEN_PRD_V1', '流水线模板/模板-需求分析PRD.md'),
          ('REVIEW_PRD_ARCH', '流水线模板/模板-评审书.md'),
          ('GEN_ARCH_V1', '流水线模板/模板-架构设计.md'),
          ('GEN_UI_V1', '流水线模板/模板-UI设计规范.md'),
          ('GEN_DETAIL_V1', '流水线模板/模板-详细设计.md'),
          ('GEN_CASES_V1', '流水线模板/模板-测试用例.md'),
          ('DEV_CODING', '流水线模板/模板-开发交付摘要.md'),
          ('INT_TEST', '流水线模板/模板-集成测试执行摘要.md')
     ) AS v(code, path)
WHERE d.id = s.definition_id
  AND d.code = 'RD_AUTO'
  AND d.version = 1
  AND d.tenant_id IS NULL
  AND s.code = v.code;
