# MyBatis XML 评审规范

| 项 | 内容 |
|---|---|
| 文件 | `rule/mybatis_xml_review.md` |
| 用途 | `ada-review` MYBATIS_XML 分片注入 |

`evidence` 引用如 `MB-SQL-01`。

---

| 编号 | 级别建议 | 规则 |
|---|---|---|
| MB-SQL-01 | 高 | `#{}` 优先；禁止无防护的 `${}` 拼接用户输入（防注入） |
| MB-SQL-02 | 高 | 动态 SQL 条件遗漏导致全表更新/删除必须拦截 |
| MB-SQL-03 | 中 | resultMap / 字段与实体属性一致；改列须同步 |
| MB-SQL-04 | 中 | 避免 `SELECT *`；明确列清单 |
| MB-SQL-05 | 中 | 大批量操作应分批或说明性能影响 |
| MB-SQL-06 | 低 | namespacepace / id 命名与 Mapper 接口方法一致 |
