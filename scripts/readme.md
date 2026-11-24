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
