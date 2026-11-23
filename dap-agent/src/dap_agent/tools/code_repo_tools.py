from __future__ import annotations

import json
from typing import Any

from langchain_core.tools import StructuredTool
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from dap_agent.tools.dap_server_client import dap_server_client

_ACTIONS_HINT = (
    "action 必须是下面某一个完整名字（不要用斜杠拼接）："
    "tool_version, git_status, git_diff, git_log, "
    "build, build_gradle, build_maven, build_vite, "
    "test, test_gradle, test_maven, test_node, test_uv, "
    "start_dev, start_gradle, start_maven, start_vite, start_uv, "
    "stop_dev, stop_port, list_processes, check_port, "
    "mvn_validate, mvn_deps_tree, gradle_deps。"
    "Gradle 工程优先分别选用 build_gradle、test_gradle、start_gradle 之一，勿写成 build/build_gradle。"
)


class RepoRefArgs(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    repo_index: int | None = Field(
        default=0,
        validation_alias=AliasChoices("repo_index", "repoIndex"),
        description="会话绑定仓库下标，从 0 开始；默认 0",
    )


class ListDirArgs(RepoRefArgs):
    relative_path: str = Field(
        default=".",
        validation_alias=AliasChoices("relative_path", "relativePath"),
        description="相对仓库根的目录，默认 .",
    )


class ReadFileArgs(RepoRefArgs):
    relative_path: str = Field(
        validation_alias=AliasChoices("relative_path", "relativePath"),
        description="相对仓库根的文件路径",
    )
    start_line: int | None = Field(
        default=None,
        validation_alias=AliasChoices("start_line", "startLine"),
        description="起始行号（从 1 起）",
    )
    end_line: int | None = Field(
        default=None,
        validation_alias=AliasChoices("end_line", "endLine"),
        description="结束行号（含）",
    )


class WriteFileArgs(RepoRefArgs):
    relative_path: str = Field(
        validation_alias=AliasChoices("relative_path", "relativePath"),
        description="相对仓库根的文件路径（禁止写 .git/hooks、.env 等敏感路径）",
    )
    content: str = Field(description="完整文件内容")


class DeleteFileArgs(RepoRefArgs):
    relative_path: str = Field(
        validation_alias=AliasChoices("relative_path", "relativePath"),
        description="相对仓库根的文件或空目录",
    )


class SearchCodeArgs(RepoRefArgs):
    query: str = Field(description="在仓库内搜索的关键词")


class RunActionArgs(RepoRefArgs):
    action: str = Field(description=f"白名单动作名。{_ACTIONS_HINT}")
    args: dict[str, str] | None = Field(
        default=None,
        description="可选参数，如 stop_port/check_port 的 port、git_diff 的 path、tool_version 的 tool、test_uv 的 path",
    )


class CheckPortArgs(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    port: int = Field(description="本机端口 1-65535")


def _dump(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False, default=str)


def build_code_repo_tools(session_id: int) -> list[StructuredTool]:
    async def list_code_repos() -> str:
        data = await dap_server_client.list_code_repos(session_id)
        return _dump(data)

    async def list_dir(repo_index: int | None = 0, relative_path: str = ".") -> str:
        data = await dap_server_client.code_list(session_id, repo_index, relative_path)
        return _dump(data)

    async def read_file(
        relative_path: str,
        repo_index: int | None = 0,
        start_line: int | None = None,
        end_line: int | None = None,
    ) -> str:
        data = await dap_server_client.code_read(
            session_id, repo_index, relative_path, start_line, end_line
        )
        return _dump(data)

    async def write_file(
        relative_path: str,
        content: str,
        repo_index: int | None = 0,
    ) -> str:
        data = await dap_server_client.code_write(session_id, repo_index, relative_path, content)
        return _dump(data)

    async def delete_file(relative_path: str, repo_index: int | None = 0) -> str:
        data = await dap_server_client.code_delete(session_id, repo_index, relative_path)
        return _dump(data)

    async def search_code(query: str, repo_index: int | None = 0) -> str:
        data = await dap_server_client.code_search(session_id, repo_index, query)
        return _dump(data)

    async def run_action(
        action: str,
        repo_index: int | None = 0,
        args: dict[str, str] | None = None,
    ) -> str:
        data = await dap_server_client.code_action(session_id, repo_index, action, args)
        return _dump(data)

    async def check_port(port: int) -> str:
        data = await dap_server_client.code_check_port(session_id, port)
        return _dump(data)

    return [
        StructuredTool.from_function(
            coroutine=list_code_repos,
            name="list_code_repos",
            description="列出本会话绑定的本地代码仓库及 repoIndex。",
        ),
        StructuredTool.from_function(
            coroutine=list_dir,
            name="list_dir",
            description="列出仓库内某相对目录下的文件/子目录（路径不可越出仓库根）。",
            args_schema=ListDirArgs,
        ),
        StructuredTool.from_function(
            coroutine=read_file,
            name="read_file",
            description="读取仓库内相对路径文件内容（可指定行号范围）。",
            args_schema=ReadFileArgs,
        ),
        StructuredTool.from_function(
            coroutine=write_file,
            name="write_file",
            description="写入/创建仓库内文件；仅允许相对路径，服务端会做路径与内容安全检查。",
            args_schema=WriteFileArgs,
        ),
        StructuredTool.from_function(
            coroutine=delete_file,
            name="delete_file",
            description="删除仓库内文件或空目录。",
            args_schema=DeleteFileArgs,
        ),
        StructuredTool.from_function(
            coroutine=search_code,
            name="search_code",
            description="在绑定仓库内按关键词搜索源码。",
            args_schema=SearchCodeArgs,
        ),
        StructuredTool.from_function(
            coroutine=run_action,
            name="run_action",
            description=(
                "在仓库根执行白名单动作（构建/测试/启动/git 探查等），禁止任意 shell。"
                f" {_ACTIONS_HINT}"
            ),
            args_schema=RunActionArgs,
        ),
        StructuredTool.from_function(
            coroutine=check_port,
            name="check_port",
            description="检查本机某端口是否已被占用，并返回监听进程的 PID 和进程名。",
            args_schema=CheckPortArgs,
        ),
    ]
