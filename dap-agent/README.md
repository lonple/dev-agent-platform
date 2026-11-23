# dap-agent

DAP 工作台推理服务（FastAPI + LangChain）。仅供 `dap-server` 内网调用。

## 启动

```bash
cp .env.example .env   # 填写 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL
uv sync
uv run dap-agent
```

默认监听 `8202`。鉴权头：`X-Internal-Token`。
