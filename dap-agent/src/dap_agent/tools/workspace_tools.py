from __future__ import annotations

import json
from typing import Any

from langchain_core.tools import StructuredTool
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from dap_agent.tools.dap_server_client import dap_server_client


class SearchArgs(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    query: str = Field(description="在会话材料中搜索的关键词，可用空格分隔多个词")


class ReadArgs(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    source: str = Field(description="文件来源：wiki 或 workspace（材料索引里的 source）")
    node_id: int = Field(
        validation_alias=AliasChoices("node_id", "nodeId"),
        description="文件节点 ID，来自材料索引的 nodeId 字段",
    )
    start_line: int | None = Field(
        default=None,
        validation_alias=AliasChoices("start_line", "startLine"),
        description="起始行号，从 1 开始",
    )
    end_line: int | None = Field(
        default=None,
        validation_alias=AliasChoices("end_line", "endLine"),
        description="结束行号（含）",
    )


class WriteArgs(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    display_name: str = Field(
        validation_alias=AliasChoices("display_name", "displayName"),
        description="写入输出目录的文件名：.md 或 .html，如 需求分析.md / UI稿.html",
    )
    content: str = Field(description="完整正文（Markdown 或 HTML）")


def _dump(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False, default=str)


def build_workspace_tools(session_id: int) -> list[StructuredTool]:
    async def search_docs(query: str) -> str:
        data = await dap_server_client.search(session_id, query)
        return _dump(data)

    async def read_docs(
        source: str,
        node_id: int,
        start_line: int | None = None,
        end_line: int | None = None,
    ) -> str:
        data = await dap_server_client.read(session_id, source, node_id, start_line, end_line)
        return _dump(data)

    async def write_output(display_name: str, content: str) -> str:
        data = await dap_server_client.write(session_id, display_name, content)
        return _dump(data)

    return [
        StructuredTool.from_function(
            coroutine=search_docs,
            name="search_docs",
            description="在本会话允许的材料（输入文件、Wiki 上下文、输出目录文件）中按关键词搜索，返回命中行与片段。",
            args_schema=SearchArgs,
        ),
        StructuredTool.from_function(
            coroutine=read_docs,
            name="read_docs",
            description=(
                "按行号读取某份材料片段。参数必须包含 source 与 node_id（或 nodeId），"
                "取值来自材料索引或 search_docs 命中结果。不要传空对象。"
            ),
            args_schema=ReadArgs,
        ),
        StructuredTool.from_function(
            coroutine=write_output,
            name="write_output",
            description="将交付物（.md 或 .html）写入本会话输出目录；同名文件会覆盖并保留旧版快照。参数需含 display_name/displayName 与 content。",
            args_schema=WriteArgs,
        ),
    ]
