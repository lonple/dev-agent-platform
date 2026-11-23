# Docker 沙箱详细设计

| 项 | 内容 |
|---|---|
| 文档类型 | 详细设计（本机可落地 + 可扩多机） |
| 配套 | `设计规划.md` §2.3；`设计/Docker沙箱-本机落地设计.md`（概要） |
| 技术选型 | 四服务均建议 Java 17 + Spring Boot 3（与 dap-server 对齐）；code-tools 打入沙箱镜像 |
| 本期范围 | 独立三服务 + DAP 名额；Docker Desktop 本机验证；**不含** CA/mTLS |
| 现状锚点 | `CodeRepoToolService` / `InternalAgentCodeRepoController` / `CodeRepoDtos` |

---

## 1. 设计目标与非目标

### 1.1 目标

1. 将仓内 **写 / 白名单 action / commit** 迁到 **webdev 容器内 code-tools**；**只读 list/read/search 留本机**（去掉轻量只读 Docker）。  

2. **dap-server** 保留：会话鉴权、材料/仓绑定、**产品资源名额**、**产品运行时 Profile**、worktree 编排、合主干/Wiki。  
3. **SandboxManager**、**sandbox-client**、**code-tools** 独立进程，HTTP 契约清晰，本机同机部署即可验收。  
4. Agent 仍调用现有 `/api/internal/agent/sessions/{id}/code/*`，对外契约尽量不变。  
5. 流水线编码/IT 等节点通过选择 **产品级 Profile** 获得 schema/端口等模板，再申请池席位并实例化为租约。  

### 1.2 非目标（本期）

- 自建 CA、mTLS、生产 join token  
- 共享盘、多机亲和生产验证  
- Firecracker 等内核隔离  
- Wiki/个人空间 FS（`AgentFsToolService`）迁入沙箱  

### 1.3 质量属性

| 属性 | 要求 |
|---|---|
| 隔离 | 开启远程沙箱后，server 不再对本机仓 ProcessBuilder/Files 写（可开关回退仅用于对比） |
| 可替换 | Manager 只认 client HTTP；client 只认 Docker + code-tools URL |
| 可观测 | leaseId 全链路透传；各服务 access 日志带 leaseId/nodeId |
| 失败可见 | 无 lease / 节点不健康 / Docker 失败 → 明确错误码，禁止静默回落本机写 |

---

## 2. 逻辑架构

```text
                    ┌─────────────────────────────────────┐
                    │ dap-agent                           │
                    └─────────────────┬───────────────────┘
                                      │ 现有 internal code API
                    ┌─────────────────▼───────────────────┐
                    │ dap-server                          │
                    │ · SandboxLeaseService（名额+Profile实例化）│
                    │ · RuntimeProfile / 资源池维护        │
                    │ · CodeRepoFacade → ManagerClient    │
                    │ · GitWorktreeService（宿主）         │
                    └─────────────────┬───────────────────┘
                                      │ HTTP + X-Dev-Token
                    ┌─────────────────▼───────────────────┐
                    │ sandbox-manager :9200               │
                    │ · NodeRegistry                      │
                    │ · LeaseRouting（lease→node/client） │
                    │ · 透传 code.*                       │
                    └─────────────────┬───────────────────┘
                                      │ HTTP
                    ┌─────────────────▼───────────────────┐
                    │ sandbox-client :9300  （每节点）      │
                    │ · DockerEngine                      │
                    │ · SandboxInstanceRegistry           │
                    │ · ReverseProxy → code-tools         │
                    └─────────────────┬───────────────────┘
                                      │ 127.0.0.1:94xx
                    ┌─────────────────▼───────────────────┐
                    │ code-tools 容器                      │
                    │ · /workspace = mount(worktree)      │
                    │ · FS + run_action + commit          │
                    └─────────────────────────────────────┘
```

**调用原则：** 业务工具流量默认 `server → manager → client → code-tools`，server 不直连 client（便于审计与多节点）。

---

## 3. 工程与模块

```text
dap-server/
  modules/sandbox/          # 新增：资源、名额、Manager 客户端、开关
  modules/agenttool/repo/   # CodeRepoToolService 改为门面分支

sandbox-manager/            # 新工程 Spring Boot
  api/NodeController
  api/LeaseController
  domain/NodeRegistry
  client/SandboxClientHttp

sandbox-client/             # 新工程 Spring Boot
  api/HealthController
  api/SandboxController
  docker/DockerSandboxService   # docker-java 或 Docker Engine HTTP
  proxy/CodeToolsProxy

code-tools/                 # 新工程 Spring Boot（瘦）
  api/FsController, ActionController, GitCommitController
  security/WorkspacePathGuard
  action/WhitelistActionExecutor   # 从 CodeRepoToolService 迁徙

images/
  dap-sandbox-base/Dockerfile
  dap-sandbox-java/Dockerfile
```

语言：一期全部 Java 17，降低迁徙成本。

---

## 4. 数据模型（DAP 库）

### 4.1 `sb_node`（运行节点）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| node_code | VARCHAR | 如 `local-dev`，唯一 |
| display_name | VARCHAR | |
| client_base_url | VARCHAR | `http://127.0.0.1:9300` |
| status | VARCHAR | `PENDING` / `ONLINE` / `OFFLINE` / `MAINT` |
| last_health_at | DATETIME | |
| docker_ok | TINYINT | 最近探活 |
| labels_json | TEXT | 能力：`{"langs":["java"]}` |
| created_at / updated_at | | |

本机：可启动时 upsert 一条 `local-dev`。

### 4.2 `sb_product_resource`（产品 Docker 资源桶）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | resourceId |
| tenant_id / product_id | | |
| resource_code | VARCHAR | 产品内唯一 |
| node_id | BIGINT | 绑节点 |
| image | VARCHAR | 如 `dap-sandbox-java:dev` |
| max_slots | INT | 默认 3 |
| status | VARCHAR | `ACTIVE` / `DISABLED` |
| created_at / updated_at | | |

语义：配额桶 + 镜像 + 节点；**不是**必须预创建空容器。  
**与 Profile 分工：** 资源桶回答「在哪跑、用哪镜像、几席」；Profile（§5）回答「跑起来用什么 schema/端口/对接环境」。资源上的 `image` 可被 Profile 的 `imageOverride` 覆盖。

### 4.3 `sb_runtime_profile`（产品运行时配置包）

见 **§5**；表结构摘要：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id / product_id | | |
| profile_code | VARCHAR | 产品内唯一，如 `default` / `it-parallel` |
| display_name | VARCHAR | |
| is_default | TINYINT | 产品默认 Profile |
| config_json | JSON/TEXT | 模板全文（§5.3） |
| status | VARCHAR | `ACTIVE` / `DISABLED` |
| version | INT | 乐观变更；实例化时写入 lease 快照 |
| created_at / updated_at | | |

### 4.4 `sb_lease`（占用）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | leaseId |
| tenant_id / product_id | | |
| resource_id | BIGINT | 池席位 |
| profile_id | BIGINT | 所用 Profile |
| profile_version | INT | 申请时版本快照 |
| runtime_instance_json | JSON/TEXT | **实例化结果**（真实 schema、端口等） |
| integration_env_code | VARCHAR | 选用的对接环境 code |
| req_no | VARCHAR | 需求号 |
| session_id | BIGINT | 可空（流水线 Job 可另挂 job_id） |
| job_id | BIGINT | 可空 |
| worktree_host_path | VARCHAR | 宿主绝对路径 |
| purpose | VARCHAR | `DEV` / `PIPE_CODE` / `PIPE_IT` / … |
| status | VARCHAR | `REQUESTED` / `ATTACHED` / `RELEASING` / `CLOSED` |
| manager_attach_token | VARCHAR | 可选，防误用 |
| expires_at | DATETIME | 超时回收 |
| created_at / updated_at / closed_at | | |

唯一约束建议：同一 `resource_id` 下 `status=ATTACHED` 计数 ≤ max_slots（应用层事务校验）。

### 4.5 会话 / Job 扩展

- `sandbox_lease_id`  
- `sandbox_resource_id`  
- `sandbox_profile_code`（冗余便于展示）  
- 流水线节点运行参数可冗余 `runtime_instance_json` 摘要供报告展示

### 4.6 Manager 内存态（可不落库）

| 结构 | 内容 |
|---|---|
| NodeRuntime | nodeCode → clientBaseUrl, lastHealth, dockerOk |
| LeaseRoute | leaseId → nodeCode, clientBaseUrl（attach 成功后写入；detach 删除） |

名额以 DAP DB 为准；Manager 只缓存路由。重启后可按 DAP「仍 ATTACHED 的 lease」再向 client 询问或要求重新 attach。

### 4.7 Client 内存态

| 结构 | 内容 |
|---|---|
| Instance | leaseId → containerId, hostPort, codeToolsBaseUrl=`http://127.0.0.1:{port}`, worktreeHostPath, injectedEnv |

持久化可选：本地文件/SQLite，便于 client 重启后 reconcile 孤儿容器。

---

## 5. 产品运行时 Profile 与流水线资源配置

### 5.1 问题与原则

自动化流水线或任务节点在 **编码 / 联调 / IT** 时，不能只选「有没有 Docker」，还要解决：库 schema、多服务端口、Redis 前缀、对接哪个依赖环境等。

**原则：**

1. **产品维护模板（Profile）**，需求/任务只生成 **实例（lease.runtime_instance_json）**。  
2. **池（resource）与配置包（Profile）分离**：池=席位与节点镜像；Profile=运行参数模板。  
3. **对接环境产品级共享**；schema/端口/前缀按需求隔离。  
4. **一任务一席位**：前端 + 多后端占用 **1 个 slot**，Profile 内分配 **多个端口**。  
5. 节点配置优先选 Profile（或产品默认），**一般不手工挑 R1/R2**（由申请算法选空席）。

```text
产品
  ├─ 沙箱资源池 sb_product_resource     ← 灶：节点 + 镜像 + 席位
  ├─ 运行时 Profile sb_runtime_profile  ← 配方：schema/端口/启动/env
  └─ 对接环境 Environment Profile       ← 调料架：网关/用户中心等（见设计规划 §9）
```

### 5.2 谁维护

| 角色 | 维护内容 |
|---|---|
| 运维 | 节点加入、镜像仓库与预拉 |
| 产品管理员 | 资源池条目；Profile；默认对接环境 |
| 流水线定义者 | 节点上选择 `profileCode` / `integrationEnvCode`（可默认） |
| 开发/Agent | 不改模板；使用实例化后的 env |

管理入口建议：产品设置 →「沙箱与运行时」三页签（资源池 / 运行时 Profile / 对接环境引用）。

### 5.3 Profile.`config_json` 结构（模板）

```json
{
  "imageOverride": null,
  "sandbox": {
    "preferredResourceCodes": [],
    "leaseTtlHours": 8,
    "releaseWorktreeOnClose": false,
    "autoCommitOnRelease": false
  },
  "database": {
    "schemaPattern": "it_{productCode}_{reqNoSafe}",
    "urlTemplate": "jdbc:postgresql://{dbHost}:{dbPort}/{dbName}?currentSchema={schema}",
    "dbHostRef": "integration.dbHost",
    "dbPortRef": "integration.dbPort",
    "dbNameRef": "integration.dbName",
    "flywayEnabled": true
  },
  "ports": {
    "pool": { "start": 18000, "end": 18999 },
    "services": [
      { "key": "web", "protocol": "http", "containerPort": 5173, "env": "WEB_PORT" },
      { "key": "api", "protocol": "http", "containerPort": 8080, "env": "SERVER_PORT" },
      { "key": "admin", "protocol": "http", "containerPort": 8081, "env": "ADMIN_PORT" }
    ]
  },
  "middleware": {
    "redisKeyPrefixPattern": "{tenantId}:{productCode}:{reqNoSafe}:",
    "mqPrefixPattern": "{productCode}.{reqNoSafe}."
  },
  "integrationEnvDefault": "dev-integration",
  "envTemplates": [
    { "name": "SPRING_PROFILES_ACTIVE", "value": "sandbox" },
    { "name": "DB_SCHEMA", "value": "{schema}" },
    { "name": "REDIS_PREFIX", "value": "{redisKeyPrefix}" }
  ],
  "startTemplates": [
    { "key": "api", "actionHint": "start_maven", "workdir": "backend" },
    { "key": "web", "actionHint": "start_vite", "workdir": "frontend" }
  ],
  "cacheMounts": {
    "m2": true,
    "npm": true
  }
}
```

占位符在申请时解析：`productCode`、`reqNo`、`reqNoSafe`（仅 `[A-Za-z0-9_]`）、`tenantId`、`schema`、以及对接环境解析出的 host 等。

### 5.4 对接环境（产品级，引用）

不按需求一人一套。产品维护 Environment（可复用设计规划 §9）：

| 字段 | 说明 |
|---|---|
| code | `dev-integration` / `qa-integration` |
| baseUrls | 网关、用户中心、下游 |
| dbHost/dbPort/dbName | 供 schema 建在共享实例上的逻辑库/schema |
| authSecretRef | 密钥库引用 |
| networkNotes | Runner 可达性说明 |

Profile 只存 `integrationEnvDefault`；节点可覆盖 `integrationEnvCode`。  
实例化时把对接环境中的地址 **合并进** `runtime_instance_json.env`。

### 5.5 实例化算法（申请名额时）

输入：`productId, reqNo, profileCode?, integrationEnvCode?, purpose, worktreeHostPath, sessionId/jobId`  

```text
1. 解析 Profile：指定 code → 否则产品 is_default=1 → 否则失败
2. 解析对接环境：节点覆盖 → Profile 默认 → 失败可仅告警（视是否强制）
3. 选资源桶：
     - 若 preferredResourceCodes 非空则按序找空席
     - 否则产品下 ACTIVE 资源，节点健康，attached < maxSlots
4. 分配端口：按 ports.services 从端口池/租约服务领取宿主端口（可先简化为顺序递增+占用表）
5. 渲染 schema / redisPrefix / mqPrefix / urlTemplate / envTemplates
6. 写入 sb_lease（profile 快照 + runtime_instance_json）占席
7. Attach：image = profile.imageOverride ?? resource.image
     将 runtime_instance_json.env 注入容器环境变量
8. 会话/Job 绑定 leaseId；返回实例摘要给流水线节点
```

**`runtime_instance_json` 示例（实例）：**

```json
{
  "profileCode": "default",
  "schema": "it_tsglxt_TSGLXT_2026_00012",
  "ports": {
    "web": { "host": 18037, "container": 5173 },
    "api": { "host": 18038, "container": 8080 },
    "admin": { "host": 18039, "container": 8081 }
  },
  "redisKeyPrefix": "1:tsglxt:TSGLXT_2026_00012:",
  "integrationEnvCode": "dev-integration",
  "env": {
    "SPRING_PROFILES_ACTIVE": "sandbox",
    "SERVER_PORT": "8080",
    "DB_SCHEMA": "it_tsglxt_TSGLXT_2026_00012",
    "WEB_PORT": "5173",
    "REDIS_PREFIX": "1:tsglxt:TSGLXT_2026_00012:"
  },
  "image": "dap-sandbox-webdev:1.0"
}
```

多服务：同一 lease、同一（或同 compose）环境内起多个进程；**slot 只扣 1**。

### 5.6 流水线 / 任务节点如何选配置

节点类型：`PIPE_CODE` / `PIPE_IT` / 人工编码会话等。

**节点可配项（存流水线定义）：**

| 配置键 | 必填 | 说明 |
|---|---|---|
| `runtimeProfileCode` | 否 | 空则产品默认 Profile |
| `integrationEnvCode` | 否 | 空则 Profile 默认 |
| `purpose` | 是 | `PIPE_CODE` / `PIPE_IT` / … |
| `acquireSandbox` | 是 | 是否申请沙箱（编码类默认 true） |

**节点不配（系统自动）：** 具体 resourceId、具体端口、具体 schema——申请时生成。

执行前钩子：

```text
ensure worktree
  → acquireLease(profile, integration, purpose, worktreePath)
  → attach + 注入 env
  → 跑 Agent / 测试
  → finally releaseLease
```

工作台展示：只读「当前需求运行时」卡片（schema、端口、对接环境、占用资源）。

### 5.7 产品侧维护 API（DAP）

| 方法 | 路径 | 说明 |
|---|---|---|
| CRUD | `/api/work/products/{id}/sandbox/resources` | 资源池 |
| CRUD | `/api/work/products/{id}/sandbox/profiles` | Profile；支持 `setDefault` |
| GET | `/api/work/products/{id}/sandbox/profiles/{code}/preview` | 用样例 reqNo 预览实例化结果（不占席） |
| GET/PUT | 对接环境 | 可挂在现有产品环境配置下，Profile 仅引用 code |

申请 API 扩展（相对 §7.1）：

```json
{
  "productId": 1,
  "reqNo": "TSGLXT-2026-00012",
  "sessionId": 10001,
  "purpose": "PIPE_CODE",
  "worktreeHostPath": "E:/.../worktrees/...",
  "profileCode": "default",
  "integrationEnvCode": "dev-integration"
}
```

响应增加 `runtimeInstance`（即实例 JSON 摘要）。

### 5.8 端口占用表（建议）

`sb_port_allocation`：`lease_id, service_key, host_port, node_id, status`  
释放 lease 时归还端口，避免多任务撞车。本机单节点也可先用内存分配器，库表二期补。

### 5.9 与镜像策略的关系

- 产品默认 Profile 建议镜像：`dap-sandbox-webdev`（JDK + Node），支撑前端 + 多后端编译启动。  
- 资源池可只登记该镜像；Profile.`imageOverride` 按需指向更瘦的 `java`/`node` 镜像。  

### 5.10 本机最小配置

每个联调产品预置：

1. 节点 `local-dev`  
2. 资源 1 条：`webdev` 镜像，maxSlots=3  
3. Profile `default`：一份 schemaPattern + 2～3 个 ports.services + `dev-integration` 占位  
4. 流水线编码节点：`runtimeProfileCode` 留空（走默认）  

---

## 6. 配置项（各进程）

### 6.1 dap-server

```yaml
dap:
  sandbox:
    enabled: false                 # true 后 code API 走远程
    manager-base-url: http://127.0.0.1:9200
    shared-token: dev-sandbox-token
    attach-on-lease: true          # 申请名额成功后自动 attach
    fallback-local: false          # 禁止静默回本机
```

### 6.2 sandbox-manager

```yaml
server.port: 9200
sandbox:
  shared-token: dev-sandbox-token
  nodes:
    - code: local-dev
      client-base-url: http://127.0.0.1:9300
  health-interval-ms: 10000
```

### 6.3 sandbox-client

```yaml
server.port: 9300
sandbox:
  shared-token: dev-sandbox-token
  node-code: local-dev
  manager-base-url: http://127.0.0.1:9200   # 可选自注册
  docker:
    host: ""                                 # 空=默认本机 Docker Desktop
  port-range: [9400, 9499]                   # code-tools 管理端口映射（业务端口见 Profile 实例）
  default-workspace-container-path: /workspace
```

### 6.4 code-tools（容器环境变量）

```text
WORKSPACE_ROOT=/workspace
DEV_TOKEN=...                    # 可选，仅接受来自 client 的调用
ACTION_TIMEOUT_SEC=300
# 另由 lease 注入：SERVER_PORT / DB_SCHEMA / REDIS_PREFIX / WEB_PORT …
```

---

## 7. 接口详细设计

公共约定：

- Header：`X-Sandbox-Token: {shared-token}`（本期）  
- Header：`X-Lease-Id`（工具透传）  
- 错误体：`{ "code": "SB_xxx", "message": "..." }`  
- 成功：与现网 `ApiResponse` 或简单 `{ ok, data }` 对齐；**code-tools 与 CodeRepoDtos 字段对齐**，减少 server 映射  

### 7.1 dap-server — 名额 / Profile API（供工作台/流水线）

| 方法 | 路径 | 说明 |
|---|---|---|
| CRUD | `/api/work/products/{id}/sandbox/resources` | 资源池 |
| CRUD | `/api/work/products/{id}/sandbox/profiles` | 运行时 Profile（§5.7） |
| GET | `/api/work/sandbox/resources?productId=` | 列表含占用数 |
| POST | `/api/work/sandbox/leases` | 申请（含实例化） |
| GET | `/api/work/sandbox/leases/{id}` | 查询（含 runtimeInstance） |
| DELETE | `/api/work/sandbox/leases/{id}` | 释放（先 detach 再关单） |

**申请请求**

```json
{
  "productId": 1,
  "reqNo": "TSGLXT-2026-00012",
  "sessionId": 10001,
  "purpose": "PIPE_CODE",
  "worktreeHostPath": "E:/.../foo-worktrees/TSGLXT-2026-00012",
  "profileCode": "default",
  "integrationEnvCode": "dev-integration"
}
```

**申请响应**

```json
{
  "leaseId": 501,
  "resourceId": 12,
  "nodeCode": "local-dev",
  "status": "ATTACHED",
  "runtimeInstance": {
    "schema": "it_tsglxt_TSGLXT_2026_00012",
    "ports": { "api": { "host": 18038, "container": 8080 } },
    "integrationEnvCode": "dev-integration"
  }
}
```

申请事务步骤：按 **§5.5 实例化算法**（解析 Profile → 选池 → 分配端口 → 写 lease → attach 并注入 env）。  
失败 → lease=`CLOSED` + 抛错。  
释放：Manager `detach` → 归还端口 → lease=`CLOSED` → 清会话字段。

### 7.2 dap-server — 现有 Agent Code API（门面行为变更）

路径保持：

- `POST .../code/list|read|write|delete|search|action`  
- `GET .../code-repos`  
- `POST .../code/check-port`（见 §6.6）  

当 `dap.sandbox.enabled=true`：

1. 解析 session → 必须有 `sandbox_lease_id` 且 status=ATTACHED  
2. 将 body（RepoRef 中路径语义见下）转发 Manager  
3. 返回 DTO 与现网一致  

**RepoRef 在沙箱模式下的语义：**

- `repoIndex` / `localPath`：仍用于 DAP 侧解析「逻辑仓」与审计；  
- 实际读写根目录 = attach 时的 `/workspace`（单 worktree 场景一轮一个根）。  
- 多仓会话（BIZ+TEST）：**一会话可申请两笔 lease** 或一 lease 多 mount（一期建议 **一仓一 lease**，会话工具带 `repoIndex` 映射到对应 leaseId）。

一期简化：**编码会话单 BIZ worktree → 单 lease**；TEST 另开会话或二期再做。

### 7.3 SandboxManager API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 自身健康 |
| GET | `/api/v1/nodes` | 节点列表 + 缓存健康 |
| POST | `/api/v1/nodes/register` | 可选；body: nodeCode, clientBaseUrl |
| POST | `/api/v1/leases/{leaseId}/attach` | 见下 |
| POST | `/api/v1/leases/{leaseId}/detach` | |
| POST | `/api/v1/leases/{leaseId}/code/list` | 透传 |
| POST | `/api/v1/leases/{leaseId}/code/read` | 透传 |
| POST | `/api/v1/leases/{leaseId}/code/write` | 透传 |
| POST | `/api/v1/leases/{leaseId}/code/delete` | 透传 |
| POST | `/api/v1/leases/{leaseId}/code/search` | 透传 |
| POST | `/api/v1/leases/{leaseId}/code/action` | 透传 |
| POST | `/api/v1/leases/{leaseId}/code/commit` | 透传 |

**AttachRequest**

```json
{
  "nodeCode": "local-dev",
  "image": "dap-sandbox-webdev:dev",
  "worktreeHostPath": "E:/ws/.../REQ",
  "env": {
    "SERVER_PORT": "8080",
    "WEB_PORT": "5173",
    "DB_SCHEMA": "it_tsglxt_TSGLXT_2026_00012",
    "REDIS_PREFIX": "1:tsglxt:TSGLXT_2026_00012:"
  },
  "portPublishes": [
    { "host": 18038, "container": 8080 },
    { "host": 18037, "container": 5173 }
  ],
  "labels": { "reqNo": "..." }
}
```

`env` / `portPublishes` 来自 DAP 已实例化的 `runtime_instance_json`（Manager/client 不解析 Profile 模板）。

**AttachResponse**

```json
{
  "leaseId": 501,
  "nodeCode": "local-dev",
  "codeToolsReady": true
}
```

Manager 逻辑：校验 token → 查节点 ONLINE 且 dockerOk → POST client `/sandboxes/attach` → 写入 LeaseRoute。  
透传 code：无路由则 `SB_LEASE_NOT_ATTACHED`。

### 7.4 sandbox-client API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | `{ status, dockerOk, dockerVersion, activeSandboxes }` |
| POST | `/api/v1/sandboxes/attach` | 创建容器 |
| POST | `/api/v1/sandboxes/{leaseId}/detach` | 停止删除 |
| POST | `/api/v1/sandboxes/{leaseId}/code/*` | 反代到 code-tools 对应路径 |

**Attach（client）实现要点**

1. 校验路径存在且为目录  
2. 分配宿主端口 `port-range`  
3. `docker run -d --name dap-sb-{leaseId}`  
   - `-v {worktreeHostPath}:/workspace`  
   - `-p 127.0.0.1:{codeToolsHostPort}:8080`（code-tools 管理口，可与业务端口分离；若 code-tools 与业务共容器需错开或侧车）  
   - 业务 `-p {host}:{container}` 来自 `portPublishes`  
   - `-e` 注入 attach.env  
   - image、非 root user（镜像约定）  
4. 等待 code-tools `/health` 就绪（超时失败则 rm 容器）  
5. 登记 Instance（含 injectedEnv）  

说明：一期可将 **code-tools 与业务共镜像共容器**，code-tools 固定听容器内管理端口（如 9100），业务用 Profile 的 8080/5173；映射时管理口与业务口一并 publish。  

**反代：** 将 `/sandboxes/{leaseId}/code/read` 转到 code-tools 管理口（路径可扁平为 `/read`）。

### 7.5 code-tools API（与 CodeRepoDtos 对齐）

建议前缀 `/code` 或根路径，与 client 映射一致：

| 方法 | 路径 | 请求/响应 |
|---|---|---|
| GET | `/health` | ok |
| POST | `/list` | ListRequest 字段（无 session）→ ListResult |
| POST | `/read` | PathRequest → ReadResult |
| POST | `/write` | WriteRequest → GenericResult |
| POST | `/delete` | DeleteRequest → GenericResult |
| POST | `/search` | SearchRequest → map |
| POST | `/action` | ActionRequest → ActionResult |
| POST | `/commit` | `{ message, authorName, authorEmail }` → GenericResult |

工作区根：`WORKSPACE_ROOT`。`relativePath` 必须经 `WorkspacePathGuard`（禁止 `..`、symlink 逃逸，逻辑对齐现有 `RepoPathSandbox`）。

**run_action 白名单：** 与现网一致（`git_*` / `build_*` / `test_*` / `start_*` / …）。  
`stop_port`：沙箱内改为杀容器内进程或拒绝「宿主机 taskkill」；本期可对 `stop_port` 返回不支持或仅容器内端口。

**commit：**

```text
git -C /workspace status
git add -A
git -c user.name=... -c user.email=... commit -m "..."
```

无 remote credential；push 不在 code-tools。

### 7.6 check-port

沙箱模式下：

- 优先在 **code-tools / 容器网络命名空间** 探测；或  
- 仍由 dap-server 本机探测并标注 `scope=host`（需在文档标明语义变化）  

一期建议：`check_port` 转发 code-tools 实现「容器内可否绑定」。

---

## 8. 核心时序

### 8.1 申请并 Attach（含 Profile 实例化）

```text
Workbench/流水线节点
  → DAP LeaseService.acquire(profileCode, integrationEnvCode, …)
       → 解析 Profile + 对接环境
       → 选资源池空席 + 分配端口
       → 渲染 runtime_instance_json
       → DB 占席
       → Manager.attach(image, env, portPublishes)
            → Client.attach → docker run + wait health
       → 会话/Job 写 leaseId
  ← ATTACHED + runtimeInstance
```

### 8.2 工具 read

```text
Agent → DAP code/read
  → 校验 lease ATTACHED
  → Manager /leases/{id}/code/read
  → Client 反代
  → code-tools /read
  ← ReadResult
```

### 8.3 释放

```text
DAP release
  → Manager.detach → Client stop&rm container
  → 归还端口分配
  → lease CLOSED，会话字段清空
```

### 8.4 超时回收（DAP 定时任务）

扫描 `ATTACHED && expires_at < now` → 走释放流程。  
client 侧可额外扫「无登记的 dap-sb-* 容器」作孤儿清理（安全网）。

---

## 9. 错误码

| code | 含义 | HTTP |
|---|---|---|
| SB_TOKEN_INVALID | 共享 token 错误 | 401 |
| SB_NODE_OFFLINE | 节点不在线/Docker 不可用 | 503 |
| SB_NO_CAPACITY | 名额满 | 409 |
| SB_PROFILE_NOT_FOUND | Profile 不存在或停用 | 404 |
| SB_PORT_EXHAUSTED | 端口池耗尽 | 409 |
| SB_LEASE_NOT_FOUND | lease 不存在 | 404 |
| SB_LEASE_NOT_ATTACHED | 未 attach 或路由丢失 | 409 |
| SB_ATTACH_FAILED | 容器启动/健康检查失败 | 502 |
| SB_PATH_FORBIDDEN | 路径越界 | 400 |
| SB_ACTION_DENIED | 动作不在白名单 | 400 |
| SB_TIMEOUT | 执行超时 | 504 |
| SB_REMOTE_DISABLED | server 未开远程且调用了远程 | 400 |

---

## 10. 安全设计（本期降级）

| 项 | 本期 | 后续 |
|---|---|---|
| 服务间认证 | 共享 `X-Sandbox-Token` | mTLS / 一节点一证 |
| 网络 | client、code-tools 端口绑 `127.0.0.1` | 节点仅内网 + 防火墙 |
| 容器 | 非 root；只 mount 任务 worktree | seccomp/网络 allowlist |
| 命令 | 白名单同现网 | 工具画像按任务类型 |
| 凭证 | 容器无 git remote 凭据 | 不变 |

---

## 11. 镜像与容器约定

```dockerfile
# 仅保留 webdev（本期默认）
FROM eclipse-temurin:21-jdk
RUN apt-get install -y git ripgrep maven nodejs ...
COPY code-tools.jar /opt/code-tools.jar
# CODE_TOOLS_PORT=9100；业务 SERVER_PORT 等由 Profile 注入
```

只读搜代码：**本机 rg**，不进容器。容器内 rg 供 action/排障。  


容器名：`dap-sb-{leaseId}`  
Label：`dap.leaseId`、`dap.reqNo` 便于清理。

Windows Docker Desktop：`worktreeHostPath` 转换为共享盘路径（实现 `HostPathMapper`）。

---

## 12. dap-server 改造点（清单）

1. Flyway：`sb_node` / `sb_product_resource` / `sb_runtime_profile` / `sb_lease`（含 runtime_instance_json）/ 可选 `sb_port_allocation`  
2. `SandboxLeaseService`（实例化算法 §5.5）+ 资源池/Profile 管理 API  
3. 流水线节点参数：`runtimeProfileCode` / `integrationEnvCode`；执行前 acquire、finally release  
4. `SandboxManagerClient`（WebClient/RestClient）；attach 携带 env + portPublishes  
5. `CodeRepoToolService`：  
   - `if (sandboxEnabled) remote... else legacy...`  
   - `fallback-local=false` 时 remote 失败直接抛错  
6. 会话创建/编码单元开始：可选自动 acquire+attach（产品默认 Profile）  
7. 会话结束/Job finally：release  
8. 审计日志增加 leaseId、nodeCode、profileCode、schema  

**不迁：** Wiki FS、非仓工具。

---

## 13. 本机部署与联调

### 13.1 进程

```text
Docker Desktop
java -jar sandbox-manager.jar
java -jar sandbox-client.jar
java -jar dap-server.jar   # dap.sandbox.enabled=true
dap-agent
```

### 13.2 冒烟用例

1. health：manager、client（dockerOk=true）  
2. 产品预置 resource + Profile `default`  
3. acquire（带 profile）→ 返回 schema/ports；docker ps 见 `dap-sb-*`  
4. 容器 env 含 DB_SCHEMA / SERVER_PORT  
5. write 文件 → 宿主 worktree 出现  
6. action=`git_status`  
7. commit → 宿主 git log  
8. release → 容器消失、端口归还、可再 acquire  
9. 占满后再 acquire → `SB_NO_CAPACITY`  

### 13.3 开发脚本（建议）

仓库根目录：`start-dev.ps1`（含 manager/client）、`build-code-tools-image.ps1`、`smoke-sandbox.ps1`、`start-sandbox-dev.ps1`

---

## 14. 测试设计

| 层级 | 内容 |
|---|---|
| code-tools 单测 | PathGuard、白名单解析、commit 干跑 |
| client 单测 | 端口映射、attach 失败回滚（mock Docker） |
| Manager 单测 | 节点下线拒绝 attach、透传 |
| DAP 单元 | Profile 占位符渲染、选池、端口分配 |
| DAP 集成 | 名额并发、节点选型、开关切换、释放 |
| 手工 E2E | §13.2 |

---

## 15. 里程碑与任务拆分

| 里程碑 | 交付 | 预估量级 |
|---|---|---|
| **D0** | 三空服务工程骨架 + health + token | 小 |
| **D1** | 资源池 + Profile 表/API；名额申请实例化（可先不接 Docker） | 中 |
| **D2** | Manager 静态节点；client attach/detach + Desktop；env/端口注入 | 中 |
| **D3** | code-tools 迁 FS+action；反代打通 | 大 |
| **D4** | 流水线节点挂钩 Profile；commit、释放、冒烟全绿 | 中 |
| **D5** | maxSlots、端口表、超时回收、webdev 镜像 | 中 |

---

## 16. 演进预留

- Manager `RuntimeAdapter`：Docker | 未来 Kata/Firecracker  
- client 自注册 + join token  
- 一会话多 lease（BIZ/TEST）  
- 出站网络 allowlist 由 DAP 下发 env  
- Profile 支持 compose 多容器模板（一任务多容器仍一席）  

接口保持 attach/code/detach，避免 DAP 绑死 Docker API；**Profile 只在 DAP 解析**。

---

## 17. 修订

| 日期 | 说明 |
|---|---|
| 2026-11-02 | 初稿：独立三服务 + DAP 名额；对齐现有 CodeRepo API；本机详细设计 |
| 2026-11-02 | 新增 §5 产品运行时 Profile：配置项、实例化、流水线选型、表结构与 API；串租约/attach env |
| 2026-11-02 | 去掉轻量 Docker：只读本机；默认镜像 webdev；缓存卷/端口表/超时回收/编码自动申请 |
| 2026-11-02 | 环境变量挂 Profile（config_json.envVars），随所选 Profile 注入 docker -e | |
