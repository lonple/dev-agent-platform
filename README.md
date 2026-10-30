# DAP 研发流程平台

## 工程

| 目录 | 说明 |
|---|---|
| `dap-server` | 后端：Java / Spring Boot 4.1 / MyBatis-Plus / Flyway / PostgreSQL |
| `dap-front` | 前端：Vue 3 / TypeScript / Vite / Element Plus |
| `doc/` | 需求与设计文档 |
| `rule/theme.md` | UI 主题 Token |

## 数据库

- `localhost:5432` / 用户 `postgres` / 密码 `root` / 库 `postgres` / **模式 `dap`**
- 无默认租户种子；Flyway 自动建表并初始化 admin

## 端口

| 服务 | 端口 |
|---|---|
| 后端 `dap-server` | **8200** |
| 前端 `dap-front` | **8201**（`/api` 代理到 8200） |

## 一键启动（推荐）

同一控制台合并打印前后端日志；**启动前检查 8200/8201，占用则先停止**；`Ctrl+C` 结束全部：

```powershell
.\start-dev.ps1
```

或双击 / 执行：

```bat
start-dev.bat
```

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
