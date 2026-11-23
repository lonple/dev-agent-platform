from __future__ import annotations

import logging
from typing import Any

import httpx

from dap_agent.config import settings

logger = logging.getLogger("dap_agent.tools")


class DapServerClient:
    """调用 dap-server 内部文档工具 API。"""

    def __init__(self) -> None:
        self.base_url = (settings.dap_server_base_url or "").rstrip("/")
        self.token = settings.dap_internal_token or ""

    def _headers(self) -> dict[str, str]:
        return {
            "X-Internal-Token": self.token,
            "Content-Type": "application/json",
        }

    async def _request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout: float = 60.0,
    ) -> Any:
        url = f"{self.base_url}{path}"
        # trust_env=False：忽略系统/环境 HTTP(S)_PROXY，避免 localhost 被代理成 502
        async with httpx.AsyncClient(timeout=timeout, trust_env=False) as client:
            if method.upper() == "GET":
                resp = await client.get(url, headers=self._headers())
            else:
                resp = await client.post(url, headers=self._headers(), json=body or {})
            if resp.status_code >= 400:
                detail = (resp.text or "")[:500]
                logger.error("dap-server %s %s -> %s %s", method.upper(), path, resp.status_code, detail)
                resp.raise_for_status()
            payload = resp.json()
        if isinstance(payload, dict) and payload.get("code", 0) != 0:
            raise RuntimeError(payload.get("message") or "dap-server 工具调用失败")
        return payload.get("data") if isinstance(payload, dict) else payload

    async def _post(self, path: str, body: dict[str, Any], timeout: float = 60.0) -> Any:
        return await self._request("POST", path, body, timeout=timeout)

    async def search(self, session_id: int, query: str) -> Any:
        return await self._post(f"/api/internal/agent/sessions/{session_id}/search", {"query": query})

    async def read(
        self,
        session_id: int,
        source: str,
        node_id: int,
        start_line: int | None = None,
        end_line: int | None = None,
    ) -> Any:
        body: dict[str, Any] = {"source": source, "nodeId": node_id}
        if start_line is not None:
            body["startLine"] = start_line
        if end_line is not None:
            body["endLine"] = end_line
        return await self._post(f"/api/internal/agent/sessions/{session_id}/read", body)

    async def write(self, session_id: int, display_name: str, content: str) -> Any:
        return await self._post(
            f"/api/internal/agent/sessions/{session_id}/write",
            {"displayName": display_name, "content": content},
        )

    async def list_code_repos(self, session_id: int) -> Any:
        return await self._request("GET", f"/api/internal/agent/sessions/{session_id}/code-repos")

    async def code_list(
        self, session_id: int, repo_index: int | None, relative_path: str | None
    ) -> Any:
        body: dict[str, Any] = {"relativePath": relative_path or "."}
        if repo_index is not None:
            body["repoIndex"] = repo_index
        return await self._post(f"/api/internal/agent/sessions/{session_id}/code/list", body)

    async def code_read(
        self,
        session_id: int,
        repo_index: int | None,
        relative_path: str,
        start_line: int | None = None,
        end_line: int | None = None,
    ) -> Any:
        body: dict[str, Any] = {"relativePath": relative_path}
        if repo_index is not None:
            body["repoIndex"] = repo_index
        if start_line is not None:
            body["startLine"] = start_line
        if end_line is not None:
            body["endLine"] = end_line
        return await self._post(f"/api/internal/agent/sessions/{session_id}/code/read", body)

    async def code_write(
        self, session_id: int, repo_index: int | None, relative_path: str, content: str
    ) -> Any:
        body: dict[str, Any] = {"relativePath": relative_path, "content": content}
        if repo_index is not None:
            body["repoIndex"] = repo_index
        return await self._post(f"/api/internal/agent/sessions/{session_id}/code/write", body)

    async def code_delete(
        self, session_id: int, repo_index: int | None, relative_path: str
    ) -> Any:
        body: dict[str, Any] = {"relativePath": relative_path}
        if repo_index is not None:
            body["repoIndex"] = repo_index
        return await self._post(f"/api/internal/agent/sessions/{session_id}/code/delete", body)

    async def code_search(self, session_id: int, repo_index: int | None, query: str) -> Any:
        body: dict[str, Any] = {"query": query}
        if repo_index is not None:
            body["repoIndex"] = repo_index
        return await self._post(f"/api/internal/agent/sessions/{session_id}/code/search", body)

    async def code_action(
        self,
        session_id: int,
        repo_index: int | None,
        action: str,
        args: dict[str, str] | None = None,
    ) -> Any:
        body: dict[str, Any] = {"action": action, "args": args or {}}
        if repo_index is not None:
            body["repoIndex"] = repo_index
        # 构建/测试可能较久，对齐服务端默认 180s
        return await self._post(
            f"/api/internal/agent/sessions/{session_id}/code/action", body, timeout=200.0
        )

    async def code_check_port(self, session_id: int, port: int) -> Any:
        return await self._post(
            f"/api/internal/agent/sessions/{session_id}/code/check-port", {"port": port}
        )


dap_server_client = DapServerClient()
