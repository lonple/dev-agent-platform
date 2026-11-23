-- 回填 UI 设计师岗位编码，供 GEN_UI / REVISE_UI 提示词初始化

UPDATE sys_position SET code = 'UI'
 WHERE (code IS NULL OR code = '')
   AND (
     name ILIKE '%UI%'
     OR name ILIKE '%设计师%'
     OR name ILIKE '%交互设计%'
     OR name ILIKE '%视觉%'
   )
   AND name NOT ILIKE '%架构%'
   AND name NOT ILIKE '%产品经理%';
