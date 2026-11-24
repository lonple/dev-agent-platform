# DAP Docker 沙箱（webdev）

## 口径（本期）

| 做 | 不做 |
|---|---|
| 产品资源池 + Runtime Profile + 名额申请/释放 | mTLS / join / 多 Runner |
| **webdev** 镜像（JDK/Maven/Node + code-tools） | 轻量只读镜像 |
| worktree 映射 + m2/npm 缓存卷 | 内核级微 VM |
| 写/删/`run_action`/commit 经容器 | 只读 list/read/search（**始终本机**） |
| 业务端口表、租约超时回收、编码节点自动申请 | |

端口：manager `9210` · client `9300` · code-tools 管理口映射 `127.0.0.1:94xx`→容器 `9100`

## 启动

```powershell
cd e:\workspace\agent-project\dev-agent-platform

# 首次构建 webdev 镜像
.\scripts\build-code-tools-image.ps1
# 或：.\scripts\start-dev.ps1 -BuildSandboxImage

.\scripts\start-dev.bat

# dap-server: dap.sandbox.enabled=true（默认镜像 dap-sandbox-webdev:dev）

# 产品页「预置 webdev 默认」或：
# POST /api/work/products/{id}/sandbox/ensure

# 申请：
# POST /api/work/sandbox/leases
# { productId, worktreeHostPath, reqNo?, sessionId?, profileCode? }
```

容器仅在 **lease attach** 时创建；release/超时回收时删除。
