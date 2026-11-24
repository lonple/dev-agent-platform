# DAP 业务自动化测试

本模块访问一套**已在运行的** DAP 开发环境（真实 Java 服务、数据库、前端页面），通过接口和浏览器验证业务。数据库、端口和构建启动都由产品「运行方案」配置，测试脚本不创建、不删除数据库，也不构建或启动服务。

## 配置来源

在沙箱中，运行方案页面的三组配置会生成所需变量，不需要手工维护脚本开关：

| 运行方案输入 | 填写内容 | 生成的变量（示例） |
| --- | --- | --- |
| 服务端口 | 每个服务的容器端口 | 端口/地址变量在「其它变量」里用 `{ports.<标识>}` 声明，例如 `SERVER_PORT={ports.api}`、`DAP_SERVER_BASE_URL=http://127.0.0.1:{ports.api}` |
| 数据库连接 | IP、端口、库名、模式、账号、密码 | 平台只提供占位符（`{database.*}`）；产品变量名由「其它变量」声明，例如 `SPRING_DATASOURCE_URL=jdbc:postgresql://{database.host}:{database.port}/{database.name}?currentSchema={schema}` |
| 沙箱执行配置 | 镜像、构建/启动/测试命令、执行超时 | `SANDBOX_BUILD_COMMAND`、`SANDBOX_START_COMMAND`、`SANDBOX_TEST_COMMAND`、`ACTION_TIMEOUT_SEC` |
| 测试配置 | 测试账号；真实模型或 Mock | `DAP_TEST_API_URL`、`DAP_TEST_UI_URL`、`DAP_TEST_USERNAME/PASSWORD`、`DAP_TEST_MODEL_MODE` |

测试与产品在同一容器内运行：接口地址默认取服务标识 `api` 的容器端口，页面地址取 `web` 的，即 `http://127.0.0.1:<端口>`；标识不是 api / web 时，在 JSON 里用 `testing.apiUrl` / `testing.uiUrl` 直接写地址。

## 本机运行

需要 Node.js 22+，以及一套已启动的 DAP（后端 + 前端）。

```powershell
npm --prefix dap-test ci
Copy-Item dap-test/.env.example dap-test/.env
# 编辑 dap-test/.env：待测地址、管理员账号、模型模式
npm test
```

Windows 默认使用已安装的 Microsoft Edge。没有 Edge 或在 Linux 中运行时，先执行 `cd dap-test; npx playwright install --with-deps chromium`。

```powershell
npm run test:api        # 业务接口、权限和租户隔离
npm run test:ui         # 实际浏览器操作
npm run test:workflow   # 任务调度、人工确认和流水线（仅 Mock 模式）
npm run check           # 测试代码类型检查
npm run report          # 打开最后一次 HTML 报告
```

## 测试数据

开发环境数据库长期保留，所以每次运行都使用本次运行编号前缀（如 `tlx3k9a1f2_owner`）创建测试用户、两个租户、产品、模板和版本，不会改动已有业务数据，也不会与上次运行冲突。测试账号需要平台管理员权限。测试数据会留在库中；需要清理时，按前缀删除对应租户和用户。

## 真实模型与 Mock

| 模式 | 执行范围 | 被测后端要求 |
| --- | --- | --- |
| `real` | 接口和页面用例 | 正常连接真实 Agent 与模型 |
| `mock` | 接口、页面及完整流程用例 | Agent、审查模型、沙箱管理地址指向 Mock 端口（运行方案选 Mock 时自动生成），且测试脚本与后端在同一主机/容器 |

Mock 模拟 Agent SSE、人工确认 JSON、固定文档、代码写入、OpenAI 兼容审查接口，以及沙箱 attach/detach 和测试命令退出码，再由 Java 完成持久化、发布、Git 提交、状态推进、重试和复测。它能验证流程是否正确走完、失败是否正确处理，不能证明真实模型的修复质量，也不能证明镜像构建、容器网络或部署可用。

## 报告与失败判断

- `playwright-report/index.html`：可浏览的用例结果。
- `reports/junit.xml`、`reports/results.json`：CI 可读取的结果。
- `reports/run.json`：运行编号、测试数据前缀、待测地址、模型模式、Git 提交和退出码。工作区未提交改动也会参与本次执行，提交号仅作为基线。
- `test-results/`：失败时的截图和 trace；用 `npx playwright show-trace 文件路径` 查看。
- 后端、前端日志由运行方案启动的服务自行输出，不在本模块。

退出码非 0 即失败，包括待测服务不可达、测试数据准备失败。环境准备失败时，以 `run.json` 为准，已有 HTML/JUnit 可能属于上一次执行。

覆盖明细和未覆盖边界见 [TEST_MATRIX.md](TEST_MATRIX.md)。已有 Java 单元测试与 Python 单元测试仍保留在各自模块中，可独立执行：

```powershell
cd dap-server
.\gradlew.bat test
cd ../dap-agent
uv run --with pytest pytest -q
```

## 在 DAP 集成测试节点中执行

在运行方案「沙箱执行配置」的测试命令中填写 `npm --prefix dap-test test`（留空时平台按根目录 `package.json` 自动识别为 `npm test`），执行超时按整套用例时长设置。沙箱镜像需预装 Node 与浏览器依赖；数据库地址须能从容器内访问。
