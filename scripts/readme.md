# 开发脚本

本目录集中存放 Windows 开发启动与沙箱镜像构建脚本。`.ps1` 包含实际逻辑，`.bat` 用于双击或从 CMD 调用对应 PowerShell 脚本，并透传参数；执行失败时会暂停窗口以便查看错误。

## 脚本用途

| 脚本 | 作用 |
| --- | --- |
| `start-dev.ps1` | 一键启动后端、前端、Agent、sandbox-manager、sandbox-client；合并彩色日志并统一 UTF-8 编码，按 `Ctrl+C` 停止子进程。 |
| `start-dev.bat` | `start-dev.ps1` 的快捷入口，使用 `-NoProfile -ExecutionPolicy Bypass` 调用 PowerShell。 |
| `build-code-tools-image.ps1` | 在 `sandbox/code-tools` 下执行 `gradlew.bat bootJar --no-daemon`，成功后运行 `docker build -t dap-sandbox-webdev:dev .`。任一步失败即退出。 |
| `build-code-tools-image.bat` | 镜像构建脚本的快捷入口，调用方式同上。 |

## 环境准备

- Windows PowerShell；Java 21；PATH 中可用的 Gradle（后端启动使用系统 Gradle）。沙箱模块和 code-tools 使用各自的 Gradle Wrapper。
- Node.js / npm，首次启动前在项目根目录执行 `npm --prefix dap-front install`。
- uv，以及满足 `dap-agent/pyproject.toml` 要求的 Python（当前为 `>=3.14.2`）；`uv run dap-agent` 会准备 Agent 的运行环境。
- 已启动并按项目配置准备好的 PostgreSQL，默认连接信息见 [项目说明](../README.md)。
- 使用沙箱时需要已启动的 Docker Desktop（Linux 容器模式）。构建过程需要下载 Gradle 依赖、基础镜像和镜像内工具。
- 配置 `dap-agent/.env` 中的 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`。文件不存在时，启动脚本会从 `.env.example` 复制；这些变量会注入后端与 Agent，供对话和代码评审使用。

## 一键启动

以下命令在项目根目录执行：

```powershell
.\scripts\start-dev.ps1

# 沙箱镜像缺失时尝试构建，再继续启动
.\scripts\start-dev.ps1 -BuildSandboxImage
```

CMD 中执行或双击 `scripts/start-dev.bat`：

```bat
scripts\start-dev.bat
scripts\start-dev.bat -BuildSandboxImage
```

| 服务 | 工作目录 | 端口 | 启动命令 |
| --- | --- | --- | --- |
| 后端 | `dap-server` | 8200 | `gradle bootRun --no-daemon` |
| 前端 | `dap-front` | 8201 | `npm run dev -- --host 0.0.0.0 --port 8201` |
| Agent | `dap-agent` | 8202 | `uv run dap-agent` |
| 沙箱管理器 | `sandbox/sandbox-manager` | 9210 | `gradlew.bat bootRun --no-daemon` |
| 沙箱客户端 | `sandbox/sandbox-client` | 9300 | `gradlew.bat bootRun --no-daemon` |

启动前会强制结束上述端口的监听进程及其子进程，包括其他程序占用这些端口的情况。日志在当前窗口汇总，前端访问地址为 <http://localhost:8201>，结束开发时按 `Ctrl+C`。

启动脚本只预检 `dap-sandbox-webdev:dev` 镜像，不会创建沙箱容器。Docker 不可用或镜像缺失时会提示并继续启动；传入 `-BuildSandboxImage` 时，仅在镜像缺失且找到 Docker 命令时尝试构建，构建失败也会继续启动。沙箱容器在 lease attach 时才创建，release 或超时回收时删除。

## 构建或更新沙箱镜像

首次使用沙箱，或修改了 code-tools / Dockerfile 后，在项目根目录执行：

```powershell
.\scripts\build-code-tools-image.ps1
```

也可执行 `scripts\build-code-tools-image.bat`。该脚本每次都会重新打包并构建镜像，标签为 `dap-sandbox-webdev:dev`；镜像包含 JDK、Maven、Node.js 等工具和 code-tools 服务，不启动容器，也不会替换已经运行的容器。

脚本根据自身位置定位项目根目录，也支持从其他工作目录通过绝对路径调用。若 PowerShell 执行策略阻止直接运行 `.ps1`，可使用对应 `.bat` 入口。

## DAP 产品沙箱运行

产品运行方案统一用 `envVars` 配置数据库、ES、模型和端口。端口变量附带 `serviceKey`（如 `SERVER_PORT=8200` 对应 `api`、`DAP_UI_PORT=8201` 对应 `web`），平台自动分配宿主映射端口。变量之间用 `{env.变量名}` 引用；旧数据库和服务端口字段在编辑保存时转换，连接值保持不变。

DAP 运行方案使用 `execution.startCommand=sh /opt/dap-scripts/sandbox-start.sh`、`execution.testCommand=sh /opt/dap-scripts/sandbox-test.sh`，`buildCommand` 可留空。启动脚本安装 Linux 依赖，使用 Gradle bootRun、Vite 和 uv 启动当前工作区的后端、前端与 Agent，等待三个服务就绪后才执行测试。退出码 78 表示环境启动失败，不进入业务修复建议。日志位于容器 `/tmp/dap-runtime/`。

缓存配置建议启用 `cacheMounts.gradle/uv/npm`，并配置 `workspaceCaches` 为 `dap-front/node_modules`、`dap-test/node_modules`、`dap-agent/.venv-linux`、`dap-server/.gradle`、`dap-server/build`。这些路径挂载独立 Linux 缓存卷，避免覆盖 Windows 的依赖和构建结果；修改 lockfile 后启动脚本会重新同步依赖。映射下载缓存不代表业务代码已经编译。

ES 连接变量为 `DAP_ES_URIS=http://host.docker.internal:9200`；测试环境固定使用 `DAP_CHAT_STEP_INDEX_NAME=dap-test-chat-step`，读写同一个索引或别名，不追加问题号或月份；该索引已预先创建。未配置固定名称时仍兼容 `DAP_CHAT_STEP_INDEX_ENV` 按月分索引的原有行为。已有数据库连接和 schema 由该产品变量配置决定，不自动改库。

镜像构建脚本从 PATH 中的 Gradle 复制发行版，镜像内安装 Node 24、uv 和 Playwright Chromium，并校验 Python 至少 3.14.2。使用 `build-code-tools-image.ps1` 构建，避免直接构建时缺失 `.build-tools` 暂存目录。

旧数据库 schema 迁移后通过 `schemaEnv` 指向对应环境变量（如 `DB_SCHEMA`），`{schema}`、JDBC 与 Flyway 使用同一配置值；未显式指定时自动生成小写 schema，避免 PostgreSQL search_path 对大写标识的解析差异。
