-- 详细设计评审 / 测试用例评审：不挂代码仓库
UPDATE pipe_unit_def
SET repo_kind = 'NONE'
WHERE task_type_code IN (
    'REVIEW_DETAIL_ARCH', 'REVIEW_DETAIL_PM', 'REVIEW_DETAIL_QA',
    'REVIEW_CASE_ARCH', 'REVIEW_CASE_PM', 'REVIEW_CASE_DEV'
);

UPDATE pipe_unit_instance
SET repo_kind = 'NONE'
WHERE task_type_code IN (
    'REVIEW_DETAIL_ARCH', 'REVIEW_DETAIL_PM', 'REVIEW_DETAIL_QA',
    'REVIEW_CASE_ARCH', 'REVIEW_CASE_PM', 'REVIEW_CASE_DEV'
);
