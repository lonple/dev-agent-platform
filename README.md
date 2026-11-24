# DAP 研发流程平台

## 工程

| 目录 | 说明 |
|---|---|
| `dap-server` | 后端：Java / Spring Boot 4.1 / MyBatis-Plus / Flyway / PostgreSQL |
| `dap-front` | 前端：Vue 3 / TypeScript / Vite / Element Plus |
| `doc/` | 需求与设计文档 |
| `rule/theme.md` | UI 主题 Token |

## 文档（V1.0.0）

- [需求文档](doc/需求/需求文档V1.0.0.md)
- [架构设计](doc/架构/架构设计V1.0.0.md)
- [详细设计](doc/详设/详细设计V1.0.0.md)

流水线、Docker 沙箱、上下文压缩及编码拆分已按职责合并到以上文档。模板与规范保留在 `doc/模板`、`doc/规范`；平台 UI 设计稿和 Mock 已移除。

## 数据库

- `localhost:5432` / 用户 `postgres` / 密码 `root` / 库 `postgres` / **模式 `dap`**
- 无默认租户种子；Flyway 自动建表并初始化 admin

## 端口

| 服务 | 端口 |
|---|---|
| 后端 `dap-server` | **8200** |
| 前端 `dap-front` | **8201**（`/api` 代理到 8200） |

## 一键启动（推荐）

同一控制台合并打印后端、前端、Agent 和两个沙箱服务的日志；**启动前清理 8200/8201/8202/9210/9300 的占用进程及其子进程**；`Ctrl+C` 结束全部：

```powershell
.\scripts\start-dev.ps1
```

或双击 / 执行：

```bat
scripts\start-dev.bat
```

脚本用途、环境准备及沙箱镜像构建方式见 [scripts/readme.md](scripts/readme.md)。

## 分别启动

```bash
# 后端
cd dap-server
gradle bootRun

# 前端
cd dap-front
npm install
npm run dev
```

- 前端：http://localhost:8201  
- 后端：http://localhost:8200  
- 账号：`admin` / `admin`

## 说明

- 用户中心为**系统级**，与当前租户无关。  
- Spring Boot 4 下 Druid Boot3 Starter 暂不兼容，连接池暂用 HikariCP。  
