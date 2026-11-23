# 配置文件评审规范（yml / properties）

| 项 | 内容 |
|---|---|
| 文件 | `rule/config_review.md` |
| 用途 | `ada-review` CONFIG 分片注入 |

`evidence` 引用如 `CFG-SEC-01`。

---

| 编号 | 级别建议 | 规则 |
|---|---|---|
| CFG-SEC-01 | 高 | 禁止提交明文密码、AccessKey、Token；使用环境变量或密钥管理 |
| CFG-SEC-02 | 高 | 生产配置不得开启 debug/危险端点（如未鉴权 actuator） |
| CFG-ENV-01 | 中 | 环境相关值用 profile/环境变量区分，避免写死生产地址 |
| CFG-PORT-01 | 低 | 端口与服务名变更需同步文档与依赖方 |
| CFG-COMPAT-01 | 中 | 删除或重命名配置项须评估调用方兼容性 |
