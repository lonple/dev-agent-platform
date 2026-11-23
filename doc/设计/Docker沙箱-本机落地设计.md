# Docker 沙箱：本机落地设计（流程 / 设计 / 实现）

| 项 | 内容 |
|---|---|
| 状态 | 分析稿（本机先跑通；CA/mTLS、多机加固二期） |
| 架构原则 | **SandboxManager、sandbox-client、code-tools 均为独立服务**（企业分层）；本机用 Docker Desktop **同机三进程**验证，不嵌进 dap-server |
| 目标环境 | 开发机 Windows + Docker Desktop；生产口径仍按 Linux |
| 配套 | `设计规划.md` §2.3；**详细设计**见 `设计/Docker沙箱-详细设计.md`（含产品运行时 Profile / 流水线选型） |
| 非目标（本文先不管） | 自建 CA、mTLS、join token 生产级轮换、多机共享盘、内核级微 VM |

---

## 1. 要解决什么

当前 `dap-server` 在本机用 `CodeRepoToolService` 直接读写仓、`ProcessBuilder` 跑白名单命令。风险是进程与副作用落在宿主机。

Docker 沙箱一期目标（本机版）：

1. **仓写 / `run_action` / commit** 由 **webdev 容器内 code-tools** 执行；**list/read/search 走本机文件**（不经 Docker）  

2. **DAP 管名额**：产品登记若干 Docker 资源，每资源默认最多 N 个需求（如 3）  
3. **独立三服务 + dap-server 门面** 跑通：申请 → attach → 工具调用 → commit → 释放  
4. `dap-agent` 仍只调 dap-server 工具 API（无感）

不追求：完整集群装机平台、跨宿主机生产安全、一需求一强隔离微 VM。

---

## 2. 服务划分（企业口径，本机同构）

四个进程边界从第一天就分开（本机仅部署在同一台机器、用 localhost 互调）：

```text
[浏览器 / dap-agent]
        │
        ▼
[dap-server]              业务：名额、会话、工具门面、worktree 编排、合并/Wiki
        │  HTTP（申请后持有 managerUrl / 经配置）
        ▼
[SandboxManager]          独立服务：无业务名额状态；节点路由；下发 attach/detach
        │  HTTP → 目标节点 clientBaseUrl
        ▼
[sandbox-client]          独立服务：每「节点」一份；本机 Docker；反代 code-tools；/health
        │  HTTP 127.0.0.1:容器映射端口
        ▼
[code-tools]              独立服务：跑在沙箱容器内；读写/搜/action/commit
```

| 服务 | 进程形态 | 本机端口示例 | 职责 |
|---|---|---|---|
| **dap-server** | 已有 | 如 8080 | 产品资源与名额；会话绑 `leaseId`；工具 API 鉴权后调 Manager；不碰 Docker、不落盘改仓（开关打开后） |
| **SandboxManager** | **独立 JVM/进程** | 如 9200 | 节点注册表（本机一条）；按 resource/node 路由；attach/detach/转发指令；执行侧是否充足（问 client） |
| **sandbox-client** | **独立 JVM/进程** | 如 9300（仅建议绑 127.0.0.1 或本机网卡） | Docker Desktop API；起停容器、mount；健康/简指标；把代码操作 HTTP 转到 code-tools |
| **code-tools** | **容器内独立进程** | 容器 8080 → 宿主 `127.0.0.1:94xx` | 无 LLM；list/read/write/delete/search/run_action/commit；路径与命令白名单 |

**不做：** Manager/client 嵌进 dap-server 同进程。同机三服务已经是企业拆分，只是还没多机。

本机「集群」退化：

- 1 个节点 = 本机上的那一个 sandbox-client  
- 产品资源 R = 配额桶 + 镜像 + 绑定该 node；并发席位限制起多少套 code-tools 容器  

---

## 3. 端到端流程

### 3.1 本机启动顺序

```text
① Docker Desktop 就绪
② 启动 SandboxManager（9200）
③ 启动 sandbox-client（9300），配置 managerUrl=http://127.0.0.1:9200
④ client 向 Manager 注册（或 Manager 配置静态节点 local-dev → 9300）
⑤ 启动 dap-server，配置 sandbox.manager-base-url=http://127.0.0.1:9200
⑥ dap-agent 照旧
```

探活：DAP 或 Manager 打 client `/health`（含 Docker 可用探测）。

### 3.2 节点「加入」（本机简化）

生产：装包 → 页面填 IP → join token → 探活。  
本机：

1. `nodeId=local-dev`，`clientBaseUrl=http://127.0.0.1:9300`  
2. client 启动注册到 Manager，或管理页「添加本机节点」写库并由 Manager 探活  
3. 不做 CA / 正式 join token（可用固定 `dev-token` 头）

### 3.3 产品资源池（DAP）

```text
产品 P
  └─ 资源 R1, R2, …（绑定 nodeId=local-dev，指定 image）
        每资源 maxSlots=3
```

申请：`reqNo` + 产品 → 选有空席的 R → `leaseId` + `resourceId` 写入会话。  
满则拒绝或排队（本机可先拒绝）。

### 3.4 编码任务主路径

```text
① dap-server：GitWorktreeService ensure 分支 + worktree（宿主路径）
② dap-server：申请名额，会话绑定 leaseId / resourceId
③ dap-server → SandboxManager：Attach(leaseId, node/resource, hostWorktreePath, image)
④ Manager → sandbox-client：Attach
⑤ client：docker run code-tools 镜像
     - mount 宿主 worktree → /workspace
     - 映射 127.0.0.1:94xx→8080
     - 登记 leaseId → containerId / localCodeToolsUrl
⑥ Agent → dap-server 工具 API
     → server → Manager（或 server 经 Manager 下发）→ client → code-tools
⑦ code-tools：git commit（不持远端凭证）
⑧ Detach：client 停并删容器（或按策略保留）→ dap-server 释放名额
⑨ push / 合主干 / Wiki：仍在 dap-server
```

工具调用默认路径：**dap-server → SandboxManager → sandbox-client → code-tools**，避免 server 直连各节点导致绕过主控（本机也可配置 server 只认 Manager 一个上游）。

### 3.5 一次 read 时序

```text
dap-agent
  → dap-server /code/read (sessionId, path)
  → 校验 lease 有效
  → SandboxManager POST /leases/{leaseId}/code/read
  → sandbox-client POST /sandboxes/{leaseId}/read
  → http://127.0.0.1:94xx/read  （该 lease 的 code-tools）
  → 返回文件内容
```

---

## 4. 设计要点

### 4.1 源码 mount，不 copy

- 宿主 worktree ↔ 容器 `/workspace`  
- Windows 路径做 Desktop 映射  
- commit 后宿主直接可见  

### 4.2 名额与容器

- DAP：`maxSlots` 限制并发 lease  
- client：建议 **一 lease 一 code-tools 容器**（本机好懂、好释放）  
- 资源 R = 配额 + 镜像 + 节点，不必 1:1 长驻空容器  

### 4.3 code-tools 必须是独立服务

- 容器内常驻 HTTP（从现有 `CodeRepoToolService` 迁出）  
- 端口只暴露给本机 client（`127.0.0.1`）  
- **不采用**「长期用 docker exec 代替 code-tools 服务」作为目标态；若联调极早期用 exec，仅作垫片，接口仍假装走 code-tools 契约，尽快换成容器内服务  

### 4.4 从 dap-server 拆出

| 迁到 code-tools | 留在 dap-server | 新建独立服务 |
|---|---|---|
| 落盘读写/搜、run_action、执行端白名单、容器内 commit | 名额、会话、门面、worktree 编排、push/合并/Wiki | Manager、client |

`CodeRepoToolService` 在 server 内改为调用 Manager 的远程客户端；开关 `dap.code.remote-sandbox=true`，关闭时行为保持现状便于对比。

### 4.5 本机安全（降级）

- 三服务 + code-tools 本机互调可用共享 `dev-token`  
- client / 容器端口优先绑回环  
- 出网策略本机可先空实现，预留配置  
- 生产安全见 `设计规划.md` §2.3.8  

---

## 5. 工程结构建议

```text
dap-server/          # 已有：名额模块 + SandboxManagerClient
sandbox-manager/     # 新独立服务（可 Java Spring Boot）
sandbox-client/      # 新独立服务（可 Java；持 Docker API）
code-tools/          # 新独立服务；打进沙箱镜像的主进程
images/
  dap-sandbox-base/
  dap-sandbox-java/  # 等
```

本机 `docker-compose` 或脚本：只负责 **code-tools 镜像构建**；Manager/client/server 用 jar 或 IDE 起三个（四个）进程。也可再提供 `compose` 把 Manager/client 容器化，但不是必须。

---

## 6. 实现里程碑（按独立服务）

### M0 — 名额 + 空壳服务

1. DAP：表/API `resource` / `lease`；管理页可简陋  
2. 拉起 **空** SandboxManager、sandbox-client：`/health`  
3. client 注册到 Manager；DAP 配置 Manager URL  

### M1 — Attach / Detach

1. client 调 Docker Desktop：按 lease run 容器 + mount worktree  
2. Manager 暴露 Attach/Detach，转调 client  
3. dap-server 申请名额成功后调 Manager Attach  

### M2 — code-tools 服务进容器

1. 迁出读写与 `run_action` 到 code-tools 工程，打镜像  
2. client 反代到 `127.0.0.1:94xx`  
3. dap-server 工具门面改走 Manager → … → code-tools  
4. 验收 read/write/`git_status`  

### M3 — commit 与释放闭环

1. code-tools commit API  
2. Detach + 释放名额  
3. 与真实会话或一条编码单元联调  

### M4 — 产品化边角

1. 同资源 3 席打满拒绝  
2. 多资源 R1/R2 模拟（仍单 client）  
3. 基础镜像分层；对照规划补网络白名单钩子  

---

## 7. API 草案（分服务）

### 7.1 dap-server（名额与门面）

- `POST /api/.../sandbox/leases` → `{ leaseId, resourceId }`  
- `DELETE /api/.../sandbox/leases/{id}`  
- `GET /api/.../sandbox/resources`  
- 已有 `/code/read|write|action|...`：内部改调 Manager  

### 7.2 SandboxManager

- `POST /api/nodes/register`（本机简化可静态配置）  
- `GET /api/nodes` / 探活汇总  
- `POST /api/leases/{leaseId}/attach` `{ resourceId, worktreeHostPath, image }`  
- `POST /api/leases/{leaseId}/detach`  
- `POST /api/leases/{leaseId}/code/{op}` 透传读写/action/commit  

### 7.3 sandbox-client

- `GET /health`（含 docker 状态）  
- `POST /sandboxes/attach` / `detach`  
- `POST /sandboxes/{leaseId}/read|write|action|commit` → 反代 code-tools  

### 7.4 code-tools（容器内）

- `POST /read` `/write` `/delete` `/list` `/search`  
- `POST /action` `{ action, args }`  
- `POST /commit` `{ message, author… }`  
- 根路径限制在 `/workspace`（及策略允许的 git 元数据）  

### 7.5 会话绑定

会话/Job：`sandboxLeaseId`、`resourceId`；无 lease 且开启远程沙箱时工具调用失败。

---

## 8. 镜像

```text
dap-sandbox-base     # OS、git、非 root、code-tools 运行时
  └─ dap-sandbox-java | node | python …
```

本机可先用精简镜像把 code-tools jar 打入验证；再固化 base 分层。

---

## 9. 与设计规划的关系

| 规划口径（§2.3） | 本文 |
|---|---|
| Manager 无业务状态 + DAP 名额 | 遵守 |
| Manager / client / code-tools 分层 | **本机即按独立服务实现** |
| 跨机调 client | 本机 Manager→client 仍走 HTTP，便于原样扩多机 |
| join token / mTLS | 不做 |
| 产品池 N×3 | DAP 名额 + 一 lease 一容器 |
| 从 server 拆执行 | 迁入 code-tools 独立服务 |

---

## 10. 风险与验收

**验收**

- [ ] 四个角色均可独立启停（server / Manager / client / 容器内 code-tools）  
- [ ] server 不配置 Manager 或无 lease 时，开启远程开关后工具失败（不静默写本机）  
- [ ] attach 后 write 落在宿主 worktree  
- [ ] `run_action`（如 `git_status`）经 code-tools 成功  
- [ ] commit 后宿主可见  
- [ ] detach 后名额归还、容器清理  
- [ ] 同资源占满 maxSlots 后申请失败  

**限制**

- Windows 挂盘与路径  
- 本机无真实多 client；多资源只是逻辑桶  
- 无 mTLS，仅开发信任模型  

---

## 11. 小结

本机落地按 **企业拆分** 做：**dap-server（名额+门面）+ 独立 SandboxManager + 独立 sandbox-client + 容器内独立 code-tools**，同机 localhost 串联，Docker Desktop 提供执行环境。  
mount worktree 承接现有 Git；CA/装机/多机加固另文。先 M0 空壳三服务，再 M1 attach，M2 迁 code-tools，M3 闭环释放。

| 日期 | 说明 |
|---|---|
| 2026-11-02 | 初稿 |
| 2026-11-02 | 调整为 Manager / client / code-tools 三独立服务；本机同机部署验证 |
