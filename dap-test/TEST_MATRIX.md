# 业务自动化覆盖清单

本次新增 64 项自动化用例：40 项 API、6 项浏览器、18 项流程。用例真实调用 Java 服务，不用 Mock 替换业务服务或数据库。执行结果见每次运行生成的报告，不以本清单代替实际结果。

## 第一阶段：业务与页面回归

| 业务 | 覆盖内容 | 用例文件 |
| --- | --- | --- |
| 登录及访问权限 | 登录、错误密码、无令牌、无租户成员关系、普通成员写操作限制 | `tests/api/core.spec.ts` |
| 产品与代码仓 | 新增、修改、删除、重复编码、跨租户访问、TEST 用途保存回显、非法用途拒绝 | `tests/api/core.spec.ts` |
| 产品版本 | 计划版本必填发布时间、创建与列表 | `tests/api/core.spec.ts` |
| Agent 岗位 | 内置岗位幂等、内置编码保护、禁止删除 | `tests/api/core.spec.ts` |
| 提示词 | 自定义内容不被初始化覆盖、岗位任务绑定去重、租户隔离 | `tests/api/core.spec.ts` |
| 需求 | 草稿→审批→计划→开发、驳回重提、非法迁移、跨租户引用拒绝 | `tests/api/core.spec.ts` |
| 问题单 | 状态保存、非法状态、跨租户读取、指派非成员拒绝 | `tests/api/core.spec.ts` |
| 权限与成员 | 平台角色权限读写、普通成员越权拒绝、成员列表隔离 | `tests/api/core.spec.ts` |
| 个人空间 | 文件增删改查、个人访问隔离、路径穿越拒绝 | `tests/api/content.spec.ts` |
| Wiki | 个人文件发布、内容回读、跨租户访问拒绝 | `tests/api/content.spec.ts` |
| 执行环境 | 中性默认值、产品隔离、初始化幂等、默认方案保护、非法 JSON、租户和写权限限制 | `tests/api/content.spec.ts` |
| 用量审计 | 管理员可读、普通成员不可读 | `tests/api/content.spec.ts` |
| 页面 | 未登录跳转、登录进入总览、TEST 仓库编辑保存、需求/修复提示词分组、未保存编辑保护、岗位深链提示词 | `tests/ui/workbench.spec.ts` |

## 第二阶段：真实流程编排

| 流程 | 覆盖内容 | 用例文件 |
| --- | --- | --- |
| 聊天 | SSE 事件、消息持久化、会话访问隔离、删除后不可读 | `tests/workflow/chat.spec.ts` |
| Job 重试 | 上游首次 503，真实调度器重试，第二次生成产物 | `tests/workflow/chat.spec.ts` |
| 流水线生命周期 | 同主体重复启动拒绝、取消持久化、跨租户访问和引用拒绝 | `tests/workflow/pipeline.spec.ts`、`requirement.spec.ts` |
| 文档交付 | Mock Agent 通过真实回调落盘，人工发布到 Wiki，校验产物内容 | `tests/workflow/pipeline.spec.ts` |
| 人工确认 | 等待确认→回灌→第二轮执行→正式产物；非确认状态禁止提交 | `tests/workflow/pipeline.spec.ts` |
| 异常 | 停止正在输出的执行、畸形确认 JSON 不被当成正式交付 | `tests/workflow/pipeline.spec.ts` |
| 需求流程 | 业务与技术双初审发布、未初审不得二审、二审挂起→恢复研发、生成发布 PRD、关闭架构/UI、二审防重复 | `tests/workflow/requirement.spec.ts` |
| 问题完整闭环 | 分析发布→写真实临时代码→修复报告发布→审查确认→测试报告发布→DONE | `tests/workflow/pipeline.spec.ts` |
| 失败修复闭环 | 首次测试失败→失败分析→人工确认→修复真实临时代码→Git 提交→平台复测通过→DONE | `tests/workflow/pipeline.spec.ts` |

## 第三阶段：外部执行使用 Mock

| 外部依赖 | 模拟内容 | 仍然真实执行的部分 |
| --- | --- | --- |
| Python Agent / LLM | SSE、固定内容、确认 JSON、503、慢响应、畸形 JSON | Java 会话、回调鉴权、文件产物、Job 与重试、发布、人工确认 |
| Java 审查模型 | OpenAI 兼容接口返回无问题的结构化结果 | Git 差异、审查任务与人工确认、状态流转 |
| Docker 管理器 | attach 成功/失败、detach、测试退出码先失败后成功 | Java 租约申请释放、失败清理、测试报告、定点写入临时工作树、自动复测 |

`mocks/server.mjs` 提供场景控制，测试只能通过运行时随机 Token 切换场景。用例串行执行，避免场景相互干扰。首次失败和复测通过会断言执行次数，避免仅凭状态判断闭环通过。

## 本次回归发现的修复

1. `SandboxProfileService` 补充产品所属租户校验，避免跨租户读取运行方案。
2. `SandboxProductController` 补充产品查看/操作权限，避免普通成员修改沙箱配置。
3. `PipelineService.pmSecond` 必须先完成 AI 初审；二审成功投入开发后禁止重复提交，挂起仍允许恢复研发。

此前的仓库 BIZ/TEST 用途修改保留，本次用接口和浏览器用例验证。

## 覆盖边界

这是一套核心业务回归基线，不等于每个接口和每种组合都已经穷尽。尚未覆盖的独立验收包括：

- 需求流水线 PRD 后的所有评审分支、合并冲突确认、多任务编码四阶段及最终合并的完整端到端组合；问题修复流水线已有完整闭环。
- 真实 Python Agent 与模型推理、工具选择、模型输出质量、上下文窗口、Token/成本准确性；Python 原有单元测试可另跑。
- 实际 Docker 镜像构建、容器网络、进程健康检查、应用部署，以及真实浏览器业务修复效果。
- Elasticsearch 日志索引及检索、附件上传/下载、大文件、并发抢占、性能和长时间断网。
- 重试耗尽、服务重启后恢复、全部轮次上限以及所有角色/权限组合。

新增业务时在相应目录补充正常路径、失败路径和权限边界；只有真正调用并断言的行为才计入覆盖。
