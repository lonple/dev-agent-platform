-- 研发流水、问题单流水：集成测试前增加代码评审节点

INSERT INTO pipe_node_def (definition_id, code, name, kind, optional_flag, parallel_group, join_group, sort_order)
SELECT d.id, 'code_review', '代码评审', 'AI', FALSE, NULL, NULL, 165
FROM pipe_definition d
WHERE d.code = 'RD_AUTO'
  AND NOT EXISTS (
      SELECT 1 FROM pipe_node_def nd
      WHERE nd.definition_id = d.id AND nd.code = 'code_review'
  );

INSERT INTO pipe_unit_def (node_def_id, code, name, position_code, task_type_code, round_tier, max_round, repo_kind, output_kind, sort_order)
SELECT nd.id, 'review', '代码评审', 'DEV', 'GEN_CODE_REVIEW', 'T3', 3, 'BIZ', 'CODE_REVIEW', 1
FROM pipe_node_def nd
JOIN pipe_definition d ON d.id = nd.definition_id
WHERE d.code = 'RD_AUTO' AND nd.code = 'code_review'
  AND NOT EXISTS (
      SELECT 1 FROM pipe_unit_def ud
      WHERE ud.node_def_id = nd.id AND ud.code = 'review'
  );

UPDATE pipe_transition t
SET to_node_code = 'code_review'
FROM pipe_definition d
WHERE t.definition_id = d.id
  AND d.code = 'RD_AUTO'
  AND t.from_node_code = 'gen_coding'
  AND t.to_node_code = 'gen_int_test';

INSERT INTO pipe_transition (definition_id, from_node_code, to_node_code, event, priority, condition_json)
SELECT d.id, 'code_review', 'gen_int_test', 'SUCCESS', 0, NULL
FROM pipe_definition d
WHERE d.code = 'RD_AUTO'
  AND NOT EXISTS (
      SELECT 1 FROM pipe_transition t
      WHERE t.definition_id = d.id
        AND t.from_node_code = 'code_review'
        AND t.to_node_code = 'gen_int_test'
  );

INSERT INTO pipe_node_def (definition_id, code, name, kind, optional_flag, parallel_group, join_group, sort_order)
SELECT d.id, 'issue_code_review', '代码评审', 'AI', FALSE, NULL, NULL, 25
FROM pipe_definition d
WHERE d.code = 'ISSUE_AUTO'
  AND NOT EXISTS (
      SELECT 1 FROM pipe_node_def nd
      WHERE nd.definition_id = d.id AND nd.code = 'issue_code_review'
  );

INSERT INTO pipe_unit_def (node_def_id, code, name, position_code, task_type_code, round_tier, max_round, repo_kind, output_kind, sort_order)
SELECT nd.id, 'review', '代码评审', 'DEV', 'GEN_ISSUE_CODE_REVIEW', 'T3', 3, 'BIZ', 'CODE_REVIEW', 1
FROM pipe_node_def nd
JOIN pipe_definition d ON d.id = nd.definition_id
WHERE d.code = 'ISSUE_AUTO' AND nd.code = 'issue_code_review'
  AND NOT EXISTS (
      SELECT 1 FROM pipe_unit_def ud
      WHERE ud.node_def_id = nd.id AND ud.code = 'review'
  );

UPDATE pipe_transition t
SET to_node_code = 'issue_code_review'
FROM pipe_definition d
WHERE t.definition_id = d.id
  AND d.code = 'ISSUE_AUTO'
  AND t.from_node_code = 'issue_fix'
  AND t.to_node_code = 'issue_int_test';

INSERT INTO pipe_transition (definition_id, from_node_code, to_node_code, event, priority, condition_json)
SELECT d.id, 'issue_code_review', 'issue_int_test', 'SUCCESS', 0, NULL
FROM pipe_definition d
WHERE d.code = 'ISSUE_AUTO'
  AND NOT EXISTS (
      SELECT 1 FROM pipe_transition t
      WHERE t.definition_id = d.id
        AND t.from_node_code = 'issue_code_review'
        AND t.to_node_code = 'issue_int_test'
  );
