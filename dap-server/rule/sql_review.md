# SQL 评审规范

| 项 | 内容 |
|---|---|
| 文件 | `rule/sql_review.md` |
| 用途 | `ada-review` SQL 分片注入 |

`evidence` 引用如 `SQL-DDL-01`。

---

| 编号 | 级别建议 | 规则 |
|---|---|---|
| SQL-DDL-01 | 高 | 破坏性 DDL（DROP/TRUNCATE）须有说明与回滚策略 |
| SQL-DML-01 | 高 | UPDATE/DELETE 必须带明确 WHERE；禁止无 WHERE |
| SQL-DML-02 | 中 | 大表变更评估锁与耗时；必要时分批 |
| SQL-IDX-01 | 中 | 新增索引说明用途；避免重复/无效索引 |
| SQL-MIG-01 | 中 | 迁移脚本可重复执行或标明一次性；失败可回滚 |
| SQL-SEC-01 | 高 | 禁止明文写入密钥/密码 |
