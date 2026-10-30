-- 去掉「个人关联共享」模型；会话上下文直接选自租户共享空间
DROP TABLE IF EXISTS sys_workspace_assoc;
