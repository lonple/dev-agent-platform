-- 凡已有工作台权限的角色，补齐流水线查看/操作（避免有 work:access 却无 pipeline:* 进不了页/调不通 API）

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p.id
FROM sys_role_permission rp
JOIN sys_permission work_p ON work_p.id = rp.permission_id AND work_p.code = 'work:access'
CROSS JOIN sys_permission p
WHERE p.code IN ('pipeline:view', 'pipeline:operate', 'pipeline:config')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission x
      WHERE x.role_id = rp.role_id AND x.permission_id = p.id
  );
