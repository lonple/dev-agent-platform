from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncIterator, Literal, TypedDict

from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage, ToolMessage

from dap_agent.context.governor import ContextGovernor, GovernResult
from dap_agent.llm.factory import create_chat_model
from dap_agent.schemas.chat import ChatStreamRequest, FileIndex
from dap_agent.tools.code_repo_tools import build_code_repo_tools
from dap_agent.tools.workspace_tools import build_workspace_tools

logger = logging.getLogger("dap_agent.workbench")

# 末轮假分片时略作让步，避免一次性冲进 TCP 缓冲、前端看不到打字机
_CONTENT_CHUNK_SLEEP = 0.012


def _is_stream_chunk_timeout(exc: BaseException) -> bool:
    name = type(exc).__name__
    if "StreamChunkTimeout" in name:
        return True
    return "No streaming chunk received" in str(exc)


def _is_coding_session(req: ChatStreamRequest) -> bool:
    idx = _normalize_file_index(req.file_index)
    code = (idx.taskTypeCode if idx else None) or ""
    name = (idx.taskTypeName if idx else None) or ""
    return _needs_code_repo(code, name) or bool(idx and idx.codeRepos)


# 文档生成类 GEN（不含编码/联调修 bug）
_DOC_GEN_CODES = frozenset(
    {
        "GEN_PRD",
        "GEN_ARCH",
        "GEN_UI",
        "GEN_DETAIL",
        "GEN_CASES",
        "GEN_ISSUE_ANALYSIS",
        "GEN_CODE_REVIEW",
        "GEN_ISSUE_CODE_REVIEW",
    }
)
_CODING_LIKE_GEN = frozenset(
    {"GEN_CODING", "GEN_ISSUE_FIX", "GEN_INT_TEST", "GEN_ISSUE_INT_TEST"}
)


def _coding_stage(req: ChatStreamRequest) -> str:
    idx = _normalize_file_index(req.file_index)
    return ((idx.codingStage if idx else None) or "").strip().upper()


def _task_type_code(req: ChatStreamRequest) -> str:
    idx = _normalize_file_index(req.file_index)
    return ((idx.taskTypeCode if idx else None) or "").strip().upper()


def _is_issue_fix_task(req: ChatStreamRequest) -> bool:
    """问题修复：成功态优先 write_file，报告后补；走交付收窗但不拆四阶段。"""
    return _task_type_code(req) == "GEN_ISSUE_FIX"


def _is_int_test_fix_task(req: ChatStreamRequest) -> bool:
    """存量集成测试修复轮：必须 write_file；平台复测，无需 write_output 报告。"""
    return _task_type_code(req) == "GEN_ISSUE_INT_TEST" and _coding_stage(req) == "INT_TEST_FIX"


def _is_code_patch_task(req: ChatStreamRequest) -> bool:
    """需要尽快 write_file 的改码任务（问题修复 / 存量测修复）。"""
    return _is_issue_fix_task(req) or _is_int_test_fix_task(req)


def _is_issue_analysis_task(req: ChatStreamRequest) -> bool:
    return _task_type_code(req) == "GEN_ISSUE_ANALYSIS"


def _write_file_settles_window(req: ChatStreamRequest) -> bool:
    """改码即结算：问题修复 EXPLORE / 存量测修复 / 编码单任务卡。"""
    if _coding_stage(req) == "CODING_TASK":
        return True
    if _is_code_patch_task(req):
        return True  # EXPLORE 用；PATCHED 硬拦另算
    return False


def _stage_deliverable_name(req: ChatStreamRequest) -> str:
    idx = _normalize_file_index(req.file_index)
    name = ((idx.stageDeliverableName if idx else None) or "").strip()
    if name:
        return name
    stage = _coding_stage(req)
    if stage == "CODING_PLAN":
        return "coding_plan.json"
    if stage == "CODING_INTEGRATE":
        return "编码完成报告.md"
    if _is_issue_fix_task(req):
        return "问题修复报告.md"
    if _is_int_test_fix_task(req):
        return ""  # 平台复测，无需 Agent 报告
    if _is_issue_analysis_task(req):
        return "问题原因分析.md"
    return ""

_READ_POINTER_MARK = "[阅读指针]"
_LIST_DIR_COOLDOWN_MARK = "[list_dir 冷却]"
_DELIVERY_HARD_BLOCK_MARK = "[交付硬拦]"
_CARD_OR_POINTER_PREFIXES = (
    "[阅读证据]",
    "[read merged]",
    "[搜索证据]",
    "[搜索索引]",
    "[read dedup]",
    _READ_POINTER_MARK,
    "[read pointer]",
    _LIST_DIR_COOLDOWN_MARK,
    _DELIVERY_HARD_BLOCK_MARK,
)
_EXPLORE_TOOL_NAMES = frozenset(
    {"read_docs", "read_file", "list_dir", "search_docs", "search_code"}
)


def _is_review_task(req: ChatStreamRequest) -> bool:
    """流水线评审类任务：收窗前必须输出，触顶硬失败。"""
    idx = _normalize_file_index(req.file_index)
    code = ((idx.taskTypeCode if idx else None) or "").strip().upper()
    name = ((idx.taskTypeName if idx else None) or "").strip()
    if code.startswith("REVIEW_"):
        return True
    return "评审" in name


def _needs_delivery_window(req: ChatStreamRequest) -> bool:
    """评审 / 文档 GEN / 编码 PLAN·TASK·汇总 / 问题修复 / 存量测修复：滚动窗内必须结算，避免只探不写。"""
    if _is_review_task(req):
        return True
    if _is_code_patch_task(req):
        return True
    stage = _coding_stage(req)
    if stage in {"CODING_PLAN", "CODING_TASK", "CODING_INTEGRATE"}:
        return True
    idx = _normalize_file_index(req.file_index)
    code = ((idx.taskTypeCode if idx else None) or "").strip().upper()
    name = ((idx.taskTypeName if idx else None) or "").strip()
    if code in _DOC_GEN_CODES:
        return True
    if code.startswith("GEN_") and code not in _CODING_LIKE_GEN:
        return True
    for kw in ("详设", "详细设计", "架构设计", "需求说明书", "用例", "UI设计"):
        if kw in name and "编码" not in name:
            return True
    return False


_REVIEW_WINDOW_NUDGE_MARK = "【评审收窗提醒】"
_DELIVERY_WINDOW_NUDGE_MARK = "【交付收窗提醒】"


def _message_text(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    return _content_to_text(content)


def _is_window_settlement_message(m: Any, *, write_file_settles: bool = False) -> bool:
    """窗口结算：write_output；改码任务还可 write_file；或确认 JSON / 本窗无意见。"""
    if isinstance(m, ToolMessage):
        name = (getattr(m, "name", None) or "").strip().lower()
        if name == "write_output":
            return True
        if write_file_settles and name == "write_file":
            return True
        return False
    if isinstance(m, AIMessage):
        text = _message_text(getattr(m, "content", None))
        if "need_human_confirm" in text:
            return True
        if "本窗无意见" in text or "本窗口无意见" in text or "本窗口无问题" in text:
            return True
        if '"phase"' in text and "confirm" in text.lower():
            return True
    return False


def _is_review_settlement_message(m: Any) -> bool:
    """兼容旧名：默认不含 write_file 结算。"""
    return _is_window_settlement_message(m, write_file_settles=False)


def _is_successful_write_file_msg(m: Any) -> bool:
    if not isinstance(m, ToolMessage):
        return False
    if (getattr(m, "name", None) or "").strip().lower() != "write_file":
        return False
    text = _message_text(getattr(m, "content", None)).lstrip()
    if not text or text.startswith(_CARD_OR_POINTER_PREFIXES):
        return False
    if text.startswith("{") or text.startswith("["):
        try:
            data = json.loads(text)
            if isinstance(data, dict) and data.get("error"):
                return False
        except Exception:  # noqa: BLE001
            pass
    return True


def _issue_fix_phase(messages: list) -> str:
    """EXPLORE：尚未改码；PATCHED：至少一次成功 write_file。"""
    for m in messages:
        if _is_successful_write_file_msg(m):
            return "PATCHED"
    return "EXPLORE"


def _unsettled_explore_count(
    messages: list,
    *,
    write_file_settles: bool = False,
    tools: frozenset[str] | None = None,
) -> int:
    """自上次结算以来的探索工具次数（search/list/read；证据卡/硬拦不计）。"""
    count_tools = tools or _EXPLORE_TOOL_NAMES
    n = 0
    for m in reversed(messages):
        if _is_window_settlement_message(m, write_file_settles=write_file_settles):
            break
        if not isinstance(m, ToolMessage):
            continue
        name = (getattr(m, "name", None) or "").strip().lower()
        text = _message_text(getattr(m, "content", None)).lstrip()
        if text.startswith(_CARD_OR_POINTER_PREFIXES):
            continue
        if name in count_tools:
            n += 1
            continue
        # name 可能未回填：用正文形态兜底（仅当计入 read 类时）
        if "read_docs" in count_tools and '"content"' in text and (
            "nodeId" in text or "node_id" in text or "relativePath" in text or "displayPath" in text
        ):
            n += 1
    return n


def _unsettled_read_count(
    messages: list, *, count_list_dir: bool = False, write_file_settles: bool = False
) -> int:
    """兼容旧接口：read（可选 list_dir）。新逻辑请用 _unsettled_explore_count。"""
    tools = {"read_docs", "read_file"}
    if count_list_dir:
        tools |= {"list_dir"}
    return _unsettled_explore_count(
        messages, write_file_settles=write_file_settles, tools=frozenset(tools)
    )


def _delivery_hard_block_explore(
    req: ChatStreamRequest, name: str, messages: list
) -> str | None:
    """达 keep 或 PATCHED 纪律：硬拦探索工具（不打后端）。"""
    from dap_agent.config import settings

    n = (name or "").strip().lower()
    if n not in _EXPLORE_TOOL_NAMES:
        return None
    keep = max(1, int(settings.context_evidence_read_keep_turns))
    deliverable = _stage_deliverable_name(req) or "正式交付物"

    if _is_code_patch_task(req):
        phase = _issue_fix_phase(messages)
        int_test_fix = _is_int_test_fix_task(req)
        report = deliverable or ("问题修复报告.md" if not int_test_fix else "")
        if phase == "PATCHED":
            if n in {"search_docs", "search_code", "list_dir"}:
                if int_test_fix:
                    return (
                        f"{_DELIVERY_HARD_BLOCK_MARK} 存量测修复已进入 PATCHED（已有 write_file）。"
                        "禁止再 search/list 逛仓；请继续 write_file 补齐失败点，"
                        "或用中文简述改动后结束（平台会检查 diff 并复测）。"
                    )
                return (
                    f"{_DELIVERY_HARD_BLOCK_MARK} 问题修复已进入 PATCHED（已有 write_file）。"
                    "禁止再 search/list 逛仓；请继续 write_file 补齐根因闭环，"
                    f"修完后再 write_output「{report}」并写 **结论：已修复**；"
                    "未修完可写 **结论：未完成**（平台会续修）。"
                    "或对话 need_human_confirm JSON（禁止 write_output 确认档）。"
                )
            patched_keep = max(1, int(settings.issue_fix_patched_read_keep))
            # PATCHED：write_file 可结算读配额
            unsettled = _unsettled_explore_count(
                messages,
                write_file_settles=True,
                tools=frozenset({"read_docs", "read_file"}),
            )
            if unsettled >= patched_keep and n in {"read_docs", "read_file"}:
                if int_test_fix:
                    return (
                        f"{_DELIVERY_HARD_BLOCK_MARK} PATCHED 后未结算校验 read 已达 {unsettled} 次"
                        f"（上限 {patched_keep}）。请 write_file 继续改码，或简述改动后结束；"
                        "禁止继续只读（平台复测，无需 write_output 报告）。"
                    )
                return (
                    f"{_DELIVERY_HARD_BLOCK_MARK} PATCHED 后未结算校验 read 已达 {unsettled} 次"
                    f"（上限 {patched_keep}）。请 write_file 继续改码，或 write_output「{report}」"
                    "（文末 **结论：已修复** / **结论：未完成**），禁止继续只读。"
                )
            return None
        # EXPLORE
        unsettled = _unsettled_explore_count(messages, write_file_settles=True)
        if unsettled >= keep:
            if int_test_fix:
                return (
                    f"{_DELIVERY_HARD_BLOCK_MARK} 存量测修复 EXPLORE 未结算探读/搜索已达 {unsettled} 次"
                    f"（keep={keep}）。禁止继续 search/list/read；立刻 write_file 改失败点对应源码。"
                )
            return (
                f"{_DELIVERY_HARD_BLOCK_MARK} EXPLORE 未结算探读/搜索已达 {unsettled} 次"
                f"（keep={keep}）。禁止继续 search/list/read；"
                f"立刻 write_file 改码，或 write_output「{report}」，"
                "或对话输出 need_human_confirm JSON（禁止 write_output 确认档）。"
            )
        return None

    if _is_issue_analysis_task(req):
        unsettled = _unsettled_explore_count(messages, write_file_settles=False)
        if unsettled >= keep:
            return (
                f"{_DELIVERY_HARD_BLOCK_MARK} 问题分析未结算探读/搜索已达 {unsettled} 次"
                f"（keep={keep}）。禁止继续 search/list/read；"
                f"立刻 write_output「{deliverable}」，或在对话输出 need_human_confirm JSON。"
            )
        return None

    if _coding_stage(req) == "CODING_TASK":
        unsettled = _unsettled_explore_count(messages, write_file_settles=True)
        if unsettled >= keep:
            return (
                f"{_DELIVERY_HARD_BLOCK_MARK} 任务卡未结算探读/搜索已达 {unsettled} 次"
                f"（keep={keep}）。禁止继续探索；立刻 write_file 或 write_output「{deliverable}」。"
            )
        return None

    if _needs_delivery_window(req) and not _is_review_task(req):
        # 文档 GEN 等：search/list/read 计入，达 keep 硬拦
        unsettled = _unsettled_explore_count(messages, write_file_settles=False)
        if unsettled >= keep:
            return (
                f"{_DELIVERY_HARD_BLOCK_MARK} 未结算探读/搜索已达 {unsettled} 次"
                f"（keep={keep}）。禁止继续探索；先 write_output「{deliverable}」结算。"
            )
    return None


def _maybe_inject_review_window_nudge(req: ChatStreamRequest, messages: list) -> list:
    """交付/评审任务：未结算探索达到 keep 时注入提醒（硬拦在 short_circuit）。"""
    if not _needs_delivery_window(req):
        return messages
    from dap_agent.config import settings

    keep = max(1, int(settings.context_evidence_read_keep_turns))
    code_patch = _is_code_patch_task(req)
    int_test_fix = _is_int_test_fix_task(req)
    stage = _coding_stage(req)
    if code_patch and _issue_fix_phase(messages) == "PATCHED":
        patched_keep = max(1, int(settings.issue_fix_patched_read_keep))
        unsettled = _unsettled_explore_count(
            messages,
            write_file_settles=False,
            tools=frozenset({"read_docs", "read_file"}),
        )
        keep = patched_keep
        write_settles_note = False
    elif code_patch or stage in {"CODING_PLAN", "CODING_TASK"} or _is_issue_analysis_task(req):
        write_settles = _write_file_settles_window(req) and not (
            code_patch and _issue_fix_phase(messages) == "PATCHED"
        )
        unsettled = _unsettled_explore_count(messages, write_file_settles=write_settles)
        write_settles_note = write_settles
    elif _is_review_task(req):
        unsettled = _unsettled_read_count(messages, count_list_dir=False, write_file_settles=False)
        write_settles_note = False
    else:
        unsettled = _unsettled_explore_count(messages, write_file_settles=False)
        write_settles_note = False
    if unsettled < keep:
        return messages
    marks = (_DELIVERY_WINDOW_NUDGE_MARK, _REVIEW_WINDOW_NUDGE_MARK)
    for m in reversed(messages[-4:]):
        if isinstance(m, HumanMessage):
            text = _message_text(getattr(m, "content", None))
            if any(mark in text for mark in marks):
                return messages
    deliverable = _stage_deliverable_name(req)
    if _is_review_task(req):
        mark = _REVIEW_WINDOW_NUDGE_MARK
        body = (
            f"{mark}未结算 read 已达 {unsettled} 次"
            f"（keep={keep}）。在继续大段 read 之前，必须先结算本窗："
            "1) write_output 写出窗口/分段评审（有 findings 或明确无意见）；或 "
            "2) 仅输出 phase=need_human_confirm 的确认 JSON；或 "
            "3) 在回复中明确「本窗无意见」并尽快 write_output。"
            "无意见也算结算。禁止连续只读不评。"
            "结算后系统会按 read_keep 压缩更早原文。"
        )
    elif stage == "CODING_PLAN":
        mark = _DELIVERY_WINDOW_NUDGE_MARK
        name = deliverable or "coding_plan.json"
        body = (
            f"{mark}未结算探读/搜索已达 {unsettled} 次"
            f"（keep={keep}）。必须先 write_output「{name}」；系统将硬拦继续探索。"
            "宁早交粗糙 tasks[]，也不要空转逛仓。"
        )
    elif stage == "CODING_TASK":
        mark = _DELIVERY_WINDOW_NUDGE_MARK
        result = deliverable or "task_result-*.json"
        body = (
            f"{mark}未结算探读/搜索已达 {unsettled} 次"
            f"（keep={keep}）。立刻 write_file 或 write_output「{result}」；"
            "继续探索将被硬拦。"
        )
    elif code_patch and _issue_fix_phase(messages) == "PATCHED":
        mark = _DELIVERY_WINDOW_NUDGE_MARK
        if int_test_fix:
            body = (
                f"{mark}已改码（PATCHED），未结算校验 read 已达 {unsettled} 次"
                f"（上限 {keep}）。请 write_file 继续或简述改动后结束；"
                "禁止再 search/list；平台复测，无需 write_output 报告。"
            )
        else:
            report = deliverable or "问题修复报告.md"
            body = (
                f"{mark}已改码（PATCHED），未结算校验 read 已达 {unsettled} 次"
                f"（上限 {keep}）。必须 write_output「{report}」；"
                "禁止再 search/list；继续只读将被硬拦。"
            )
    elif code_patch:
        mark = _DELIVERY_WINDOW_NUDGE_MARK
        if int_test_fix:
            body = (
                f"{mark}EXPLORE 未结算探读/搜索已达 {unsettled} 次"
                f"（keep={keep}）。立刻 write_file 改失败点源码；继续探索将被硬拦。"
            )
        else:
            report = deliverable or "问题修复报告.md"
            body = (
                f"{mark}EXPLORE 未结算探读/搜索已达 {unsettled} 次"
                f"（keep={keep}）。立刻 write_file；改完再 write_output「{report}」。"
                "继续探索将被硬拦。"
            )
    elif _is_issue_analysis_task(req):
        mark = _DELIVERY_WINDOW_NUDGE_MARK
        name = deliverable or "问题原因分析.md"
        body = (
            f"{mark}未结算探读/搜索已达 {unsettled} 次"
            f"（keep={keep}）。立刻 write_output「{name}」，"
            "或对话输出 need_human_confirm；继续探索将被硬拦。"
        )
    else:
        mark = _DELIVERY_WINDOW_NUDGE_MARK
        hint = f"「{deliverable}」" if deliverable else "大纲/章节草稿或阶段性要点"
        body = (
            f"{mark}未结算探读/搜索已达 {unsettled} 次"
            f"（keep={keep}）。必须先 write_output 结算："
            f"写出 {hint}。继续探索将被硬拦。"
        )
        if write_settles_note:
            body += " write_file 也算结算。"
    return list(messages) + [HumanMessage(content=body)]


def _norm_range_token(args: dict, body: dict | None = None) -> str:
    body = body or {}
    start = (
        body.get("startLine")
        if body.get("startLine") is not None
        else args.get("start_line", args.get("startLine"))
    )
    end = (
        body.get("endLine")
        if body.get("endLine") is not None
        else args.get("end_line", args.get("endLine"))
    )
    if start is None and end is None:
        return "all"
    return f"{start or 1}-{end or 'end'}"


def _read_match_identity(name: str, args: dict, body: dict | None = None) -> str | None:
    """同范围判定键：代码用 path；文档用 source+nodeId（不依赖 displayPath）。"""
    args = args or {}
    body = body or {}
    rng = _norm_range_token(args, body)
    n = (name or "").strip().lower()

    rel = (
        body.get("relativePath")
        or body.get("relative_path")
        or args.get("relative_path")
        or args.get("relativePath")
    )
    if rel or n == "read_file":
        path = str(rel or "").replace("\\", "/").strip()
        if not path:
            return None
        return f"code|{path}|{rng}"

    source = body.get("source") or args.get("source") or ""
    node_id = (
        body.get("nodeId")
        if body.get("nodeId") is not None
        else body.get("node_id")
        if body.get("node_id") is not None
        else args.get("node_id", args.get("nodeId"))
    )
    if source or node_id is not None or n == "read_docs":
        src = str(source or "").strip() or "-"
        nid = str(node_id if node_id is not None else "-")
        if nid == "-" and src == "-":
            return None
        return f"doc|{src}|{nid}|{rng}"
    return None


def _read_call_key(name: str, args: dict) -> str | None:
    return _read_match_identity(name, args or {})


def _prior_full_read_exists(messages: list, name: str, args: dict) -> bool:
    from dap_agent.context.governor import (
        _is_card_text,
        _msg_text,
        _parse_json_object,
        _tool_call_args_index,
    )

    key = _read_match_identity(name, args or {})
    if not key:
        return False
    args_by_id = _tool_call_args_index(messages)
    for m in messages:
        if not isinstance(m, ToolMessage):
            continue
        text = _msg_text(m)
        if _is_card_text(text) or text.lstrip().startswith(
            (_READ_POINTER_MARK, "[read pointer]", _LIST_DIR_COOLDOWN_MARK)
        ):
            continue
        cid = getattr(m, "tool_call_id", None) or ""
        prior_args = dict(args_by_id.get(cid) or {})
        body = _parse_json_object(text)
        tool_name = (getattr(m, "name", None) or name or "").strip()
        k = _read_match_identity(tool_name, prior_args, body)
        if k != key:
            continue
        content = str(body.get("content") or "")
        if content.strip() or (len(text) > 80 and not text.lstrip().startswith("[")):
            return True
    return False


def _list_dir_path_key(args: dict) -> str:
    raw = (
        args.get("relative_path")
        or args.get("relativePath")
        or args.get("path")
        or args.get("dir")
        or "."
    )
    return str(raw).replace("\\", "/").strip() or "."


def _list_dir_in_cooldown(messages: list, args: dict, *, lookback_tools: int = 16) -> bool:
    """近期已对同一 path 做过 list_dir 则冷却（不计冷却桩本身）。"""
    from dap_agent.context.governor import _msg_text, _tool_call_meta_index

    want = _list_dir_path_key(args)
    meta_by_id = _tool_call_meta_index(messages)
    seen = 0
    for m in reversed(messages):
        if not isinstance(m, ToolMessage):
            continue
        seen += 1
        if seen > lookback_tools:
            break
        text = _msg_text(m).lstrip()
        if text.startswith(_LIST_DIR_COOLDOWN_MARK):
            continue
        name = (getattr(m, "name", None) or "").strip().lower()
        cid = getattr(m, "tool_call_id", None) or ""
        meta = meta_by_id.get(cid) or {}
        if not name:
            name = str(meta.get("name") or "").strip().lower()
        if name != "list_dir":
            continue
        prior = _list_dir_path_key(dict(meta.get("args") or {}))
        if prior == want:
            return True
    return False


def _count_real_list_dir(messages: list) -> int:
    """会话内真实 list_dir 次数（不含冷却桩）。"""
    from dap_agent.context.governor import _msg_text

    n = 0
    for m in messages:
        if not isinstance(m, ToolMessage):
            continue
        name = (getattr(m, "name", None) or "").strip().lower()
        if name != "list_dir":
            continue
        text = _msg_text(m).lstrip()
        if text.startswith(_LIST_DIR_COOLDOWN_MARK):
            continue
        n += 1
    return n


def _is_confirm_output_name(display_name: str | None) -> bool:
    """确认档文件名（含「确认」或 *-confirm*），禁止用 write_output 代替对话 JSON。"""
    name = (display_name or "").strip().lower()
    if not name:
        return False
    return "确认" in name or "confirm" in name


def _maybe_short_circuit_tool(
    name: str, args: dict, messages: list, *, req: ChatStreamRequest | None = None
) -> str | None:
    """交付硬拦 / 同范围再读 / list_dir 冷却与限次：不打后端，回指针。"""
    n = (name or "").strip()
    if req is not None:
        hard = _delivery_hard_block_explore(req, n, messages)
        if hard is not None:
            return hard
        # 问题分析/修复：禁止把「找人确认」写成确认文档，必须对话 need_human_confirm JSON
        if n == "write_output" and (
            _is_issue_fix_task(req) or _is_issue_analysis_task(req)
        ):
            disp = str(args.get("display_name") or args.get("displayName") or "")
            if _is_confirm_output_name(disp):
                return json.dumps(
                    {
                        "error": (
                            "禁止 write_output 确认档（含文件名「确认」）。"
                            "有不确定时请在对话正文只输出 phase=need_human_confirm 的 JSON；"
                            "确定后 write_output 正式文档（问题原因分析.md / 问题修复报告.md）。"
                        )
                    },
                    ensure_ascii=False,
                )
    if n in {"read_docs", "read_file"} and _prior_full_read_exists(messages, n, args or {}):
        key = _read_call_key(n, args or {}) or "?"
        return (
            f"{_READ_POINTER_MARK} key={key} 同范围正文已在上文；"
            "请基于已有内容继续，或先 write_output / write_file 结算后再开新窗。"
            "禁止同范围反复 read（不会刷新 keep）。"
        )
    if n == "write_file" and req is not None and _coding_stage(req) == "CODING_PLAN":
        return (
            json.dumps(
                {
                    "error": "CODING_PLAN 禁止 write_file 改业务代码；请 write_output coding_plan.json。"
                },
                ensure_ascii=False,
            )
        )
    if n == "run_action" and req is not None and _is_int_test_fix_task(req):
        act = str((args or {}).get("action") or "").strip().lower()
        if act in {
            "test",
            "test_gradle",
            "test_maven",
            "test_node",
            "test_uv",
            "build",
            "build_gradle",
            "build_maven",
        }:
            return json.dumps(
                {
                    "error": (
                        "存量测修复轮禁止 run_action 跑测/构建；请 write_file 改失败点源码。"
                        "平台会在你结束后检查 diff 并自动复测。"
                    )
                },
                ensure_ascii=False,
            )
    if n == "list_dir":
        if req is not None:
            from dap_agent.config import settings

            stage = _coding_stage(req)
            if stage == "CODING_PLAN":
                cap = max(1, int(settings.coding_plan_max_list_dir))
                used = _count_real_list_dir(messages)
                if used >= cap:
                    return (
                        f"{_LIST_DIR_COOLDOWN_MARK} PLAN 阶段 list_dir 已达上限 {cap} 次；"
                        "请基于已有信息 write_output coding_plan.json，改用 search_code/read。"
                    )
            elif stage == "CODING_TASK":
                cap = max(1, int(settings.coding_task_max_list_dir))
                used = _count_real_list_dir(messages)
                if used >= cap:
                    return (
                        f"{_LIST_DIR_COOLDOWN_MARK} 任务卡 list_dir 已达上限 {cap} 次；"
                        "请按 touch/goal 定点 search_code/read_file 后 write_file，"
                        "再 write_output task_result。"
                    )
            elif _is_code_patch_task(req):
                cap = max(1, int(settings.issue_fix_max_list_dir))
                used = _count_real_list_dir(messages)
                if used >= cap:
                    if _is_int_test_fix_task(req):
                        return (
                            f"{_LIST_DIR_COOLDOWN_MARK} 存量测修复 list_dir 已达上限 {cap} 次；"
                            "请按失败报告定点 read_file 后立刻 write_file；平台复测无需报告。"
                        )
                    return (
                        f"{_LIST_DIR_COOLDOWN_MARK} 问题修复 list_dir 已达上限 {cap} 次；"
                        "请按《问题原因分析》定点 search_code/read_file 后 write_file，"
                        "再 write_output 问题修复报告.md；不确定则对话 need_human_confirm（禁止写确认档）。"
                    )
            elif _is_issue_analysis_task(req):
                cap = max(1, int(settings.issue_analysis_max_list_dir))
                used = _count_real_list_dir(messages)
                if used >= cap:
                    return (
                        f"{_LIST_DIR_COOLDOWN_MARK} 问题分析 list_dir 已达上限 {cap} 次；"
                        "请 write_output 问题原因分析.md 或输出 need_human_confirm。"
                    )
        if _list_dir_in_cooldown(messages, args or {}):
            path = _list_dir_path_key(args or {})
            return (
                f"{_LIST_DIR_COOLDOWN_MARK} path={path} 近期已列出；"
                "请改用 search_docs/search_code 或 read，勿重复 list_dir。"
            )
    return None


def _make_governor(req: ChatStreamRequest) -> ContextGovernor:
    from dap_agent.config import settings

    coding = _is_coding_session(req)
    return ContextGovernor(
        max_round_tokens=settings.context_max_round_tokens,
        soft_ratio=settings.context_soft_ratio,
        hard_ratio=settings.context_hard_ratio,
        template_ratio=settings.context_template_ratio,
        sliding_k=(
            settings.context_sliding_k_coding if coding else settings.context_sliding_k_design
        ),
        sliding_k_min=settings.context_sliding_k_min,
        evidence_fresh_search_turns=settings.context_evidence_fresh_search_turns,
        evidence_read_keep_turns=settings.context_evidence_read_keep_turns,
    )


def _apply_context_governor(
    req: ChatStreamRequest, messages: list
) -> tuple[list, GovernResult]:
    gov = _make_governor(req)
    result = gov.govern(messages, is_coding=_is_coding_session(req), allow_reject=True)
    if result.downgrade_level != "none" or result.rejected:
        logger.info(
            "context downgrade run_id=%s level=%s mode=%s template=%s tokens≈%s/%s rejected=%s",
            req.run_id,
            result.downgrade_level,
            result.dialog_mode,
            result.template_level,
            result.estimated_tokens,
            result.max_round_tokens,
            result.rejected,
        )
    return result.messages, result


def _max_tool_rounds(req: ChatStreamRequest) -> int:
    """开发/编码任务需要更多读写轮次，避免骨架未写完就被迫收尾。

    不设上万轮：上下文会线性膨胀，费用与耗时失控；不够可再发「继续」。
    CODING_PLAN / TASK / INTEGRATE / 问题修复·分析 用短预算，防止空转耗尽 256。
    问题修复进入 PATCHED 后，主循环会把上限收紧为「当前轮+wrap」。
    """
    from dap_agent.config import settings

    stage = _coding_stage(req)
    if stage == "CODING_PLAN":
        return max(1, int(settings.max_tool_rounds_coding_plan))
    if stage == "CODING_TASK":
        return max(1, int(settings.max_tool_rounds_coding_task))
    if stage == "CODING_INTEGRATE":
        return max(1, int(settings.max_tool_rounds_coding_integrate))
    if _is_code_patch_task(req):
        # 初始仅给 EXPLORE 预算；首次 write_file 后主循环再放开 wrap
        return max(1, int(settings.max_tool_rounds_issue_fix_explore))
    if _is_issue_analysis_task(req):
        return max(1, int(settings.max_tool_rounds_issue_analysis))
    idx = _normalize_file_index(req.file_index)
    session_type = (
        req.session_type or (idx.sessionType if idx else None) or ""
    ).strip().upper()
    has_code = bool(idx and idx.codeRepos)
    if session_type == "TASK" and has_code:
        return max(1, int(settings.max_tool_rounds_code))
    if session_type == "TASK":
        return max(1, int(settings.max_tool_rounds_task))
    return max(1, int(settings.max_tool_rounds_default))


class StreamEvent(TypedDict, total=False):
    type: Literal["content", "step", "usage"]
    delta: str
    step: str
    message: str
    llmRound: int
    detail: dict[str, Any]
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


def _usage_from_message(msg: Any) -> dict[str, int]:
    """从 LangChain 消息中尽力提取 token 用量。"""
    prompt = completion = total = 0
    meta = getattr(msg, "usage_metadata", None)
    if isinstance(meta, dict):
        prompt = int(meta.get("input_tokens") or meta.get("prompt_tokens") or 0)
        completion = int(meta.get("output_tokens") or meta.get("completion_tokens") or 0)
        total = int(meta.get("total_tokens") or 0)
    if prompt == 0 and completion == 0:
        resp = getattr(msg, "response_metadata", None)
        if isinstance(resp, dict):
            usage = resp.get("token_usage") or resp.get("usage") or {}
            if isinstance(usage, dict):
                prompt = int(usage.get("prompt_tokens") or usage.get("input_tokens") or 0)
                completion = int(
                    usage.get("completion_tokens") or usage.get("output_tokens") or 0
                )
                total = int(usage.get("total_tokens") or 0)
    if total <= 0:
        total = prompt + completion
    return {
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": total,
    }


def _usage_event(acc: dict[str, int]) -> StreamEvent:
    return {
        "type": "usage",
        "prompt_tokens": int(acc.get("prompt_tokens") or 0),
        "completion_tokens": int(acc.get("completion_tokens") or 0),
        "total_tokens": int(acc.get("total_tokens") or 0),
    }


def _add_usage(acc: dict[str, int], msg: Any) -> None:
    u = _usage_from_message(msg)
    acc["prompt_tokens"] = int(acc.get("prompt_tokens") or 0) + u["prompt_tokens"]
    acc["completion_tokens"] = int(acc.get("completion_tokens") or 0) + u[
        "completion_tokens"
    ]
    acc["total_tokens"] = int(acc.get("total_tokens") or 0) + u["total_tokens"]


def _skill_text(req: ChatStreamRequest) -> str:
    items = list(req.skills or [])
    if req.skill is not None:
        items.append(req.skill)
    labels: list[str] = []
    for s in items:
        if s.custom:
            labels.append(s.custom)
        elif s.name:
            labels.append(s.name)
    return "、".join(labels) if labels else "未指定"


def _normalize_file_index(raw: Any) -> FileIndex | None:
    if raw is None:
        return None
    if isinstance(raw, FileIndex):
        return raw
    if isinstance(raw, dict):
        try:
            return FileIndex.model_validate(raw)
        except Exception:  # noqa: BLE001
            return None
    return None


def _is_auto_test_task(task_type_code: str | None, task_type_name: str | None) -> bool:
    """与 dap-server CodingTaskSupport.isAutoTestTask 对齐。"""
    code = (task_type_code or "").strip().upper()
    exact = {
        "AUTO_TEST",
        "AUTOTEST",
        "AUTOMATION_TEST",
        "AT_TEST",
        "TEST_AUTO",
        "TEST_AUTOMATION",
        "QA_AUTO",
        "QA_AUTOMATION",
        "ATEST",
    }
    if code in exact:
        return True
    if any(
        token in code
        for token in ("AUTO_TEST", "AUTOTEST", "AUTOMATION_TEST", "TEST_AUTO", "AT_TEST")
    ):
        return True
    name = (task_type_name or "").strip()
    return (
        "自动化测试" in name
        or "自动化测" in name
        or ("自动化" in name and "测试" in name)
    )


def _is_coding_task(task_type_code: str | None, task_type_name: str | None) -> bool:
    """与 dap-server CodingTaskSupport.isCodingTask 对齐。"""
    code = (task_type_code or "").strip().upper()
    exact = {"CODING", "CODE", "DEV", "DEVELOP", "BIANMA", "ENCODE", "IMPLEMENT"}
    if code in exact:
        return True
    if any(token in code for token in ("CODING", "CODE", "BIANMA")):
        return True
    return "编码" in (task_type_name or "")


def _needs_code_repo(task_type_code: str | None, task_type_name: str | None) -> bool:
    return _is_coding_task(task_type_code, task_type_name) or _is_auto_test_task(
        task_type_code, task_type_name
    )


def _file_index_text(idx: FileIndex | None) -> str:
    if idx is None or not idx.files:
        return "（当前会话暂无挂载材料）"
    lines = []
    for f in idx.files:
        lines.append(
            f"- [{f.role}] {f.source}:{f.nodeId} {f.displayPath}"
            + (f" (~{f.approxChars}字)" if f.approxChars else "")
        )
    return "\n".join(lines)


def build_system_prompt(req: ChatStreamRequest) -> str:
    idx = _normalize_file_index(req.file_index)
    out_label = (idx.outputDirLabel if idx else None) or "根目录"
    parts = [
        "你是 DAP 开发智能体工作台助手。",
        f"当前岗位：{req.position.name or '未指定'}",
        f"当前技能：{_skill_text(req)}",
        "请用简洁、专业的中文回答，聚焦软件研发生命周期相关问题。",
        "直接输出对用户有用的正文，不要输出内部思考过程标签。",
    ]
    if req.tools_enabled and req.session_id:
        parts.extend(
            [
                "",
                "你可以使用工具查阅与写入本会话材料：",
                "- search_docs：关键词搜索",
                "- read_docs：按 source+nodeId 读取片段",
                "- write_output：把交付物写入本会话配置的输出工作目录"
                f"（当前输出目录：{out_label}）。文件会落到该目录下，不要写到其他位置。"
                "文件名可用 .md（文档）、.html（页面/UI 稿）或 .json（任务卡/结构化结果）；无扩展名时默认 .md。",
                "不要臆造材料内容；需要依据时先搜索再阅读。",
                "凡是需要产出文档/交付物（需求说明、设计稿、纪要、HTML 页面等），必须调用 write_output 落盘；",
                "对话里只需简短说明已写入的文件名与要点，不要把整篇文档只贴在聊天里。",
                "",
                f"会话类型：{req.session_type or (idx.sessionType if idx else None) or '未知'}",
                f"输出目录：{out_label}",
            ]
        )
        if idx and idx.taskTypeName:
            parts.append(f"任务类型：{idx.taskTypeName}")
        stage = _coding_stage(req)
        if stage:
            parts.append(f"编码阶段：{stage}")
        if idx and idx.codingTaskId:
            parts.append(f"当前任务卡：{idx.codingTaskId}")
        deliverable = _stage_deliverable_name(req)
        if deliverable:
            parts.append(f"本阶段交付文件名：{deliverable}（必须 write_output 该名）")
        if idx and idx.taskRemark:
            parts.append(f"任务备注：{idx.taskRemark}")
        if idx and idx.requireDeliverable and stage not in {"CODING_PLAN", "CODING_TASK"}:
            parts.append("本任务要求产出交付物，完成后务必 write_output。")
        if idx and idx.standardsContent and stage != "CODING_PLAN":
            path_hint = f"「{idx.standardsPath}」" if idx.standardsPath else "会话绑定的规范"
            parts.extend(
                [
                    "",
                    f"## 必须遵守的规范（来自知识库{path_hint}）",
                    "撰写与 write_output 的交付物时，必须遵守下列规范中的约束、命名、结构与禁止项；"
                    "不得与规范冲突。若规范与输出参考模板在「结构排版」上不一致，以模板定结构，以规范定约束与内容要求。"
                    "可先用 read_docs 复核 role=standards 的材料。",
                    "",
                    idx.standardsContent,
                ]
            )
        if idx and idx.deliverableTemplate and stage not in {"CODING_PLAN", "CODING_TASK"}:
            if idx.outputTemplatePath:
                parts.append(
                    f"输出参考模板（来自知识库「{idx.outputTemplatePath}」）。"
                    "调用 write_output 时请遵循其章节结构、字段与写法，按实际内容填写，不要照抄占位示例："
                )
            else:
                parts.append("交付物模板提示（请在 write_output 时参考其结构与写法）：")
            parts.append(idx.deliverableTemplate)
        if idx and _needs_code_repo(idx.taskTypeCode, idx.taskTypeName) and not idx.codeRepos:
            parts.extend(
                [
                    "",
                    "重要：本任务类型（编码/自动化测试）需要绑定本地代码仓库后，才会开放与开发任务相同的"
                    "仓内读写与 run_action/check_port 白名单执行能力。"
                    "当前会话未绑定任何代码仓库，因此工具仅有 search_docs、read_docs、write_output，没有执行能力。"
                    "请先请用户编辑本任务会话，填写本地仓库绝对路径（例如 E:\\workspace\\book-manage）后再继续；"
                    "不要声称“设计上只有 3 个工具”或编造已执行测试的结果。",
                ]
            )
        if idx and idx.codeRepos:
            parts.append("本地代码仓库（仅本机路径，当前版本）：")
            for i, p in enumerate(idx.codeRepos):
                parts.append(f"- repoIndex={i}: {p}")
            auto_test = _is_auto_test_task(idx.taskTypeCode, idx.taskTypeName)
            task_kind = "自动化测试" if auto_test else "编码"
            if stage == "CODING_PLAN":
                from dap_agent.config import settings

                cap = max(1, int(settings.coding_plan_max_list_dir))
                parts.extend(
                    [
                        "",
                        "CODING_PLAN 可用工具（只为拆任务卡，禁止改业务代码）：",
                        "- search_docs / read_docs / search_code / list_code_repos",
                        f"- list_dir（全阶段最多 {cap} 次）/ read_file（只读摸边界）",
                        "- write_output → coding_plan.json（本阶段唯一交付）",
                        "- 禁止 write_file / delete_file；run_action 仅可偶尔 git_status，勿反复",
                    ]
                )
            else:
                parts.extend(
                    [
                        "",
                        f"{task_kind}任务可用代码工具（与开发任务相同的 LLM 权限白名单；路径不可越出绑定仓库；命令仅白名单）：",
                        "- list_code_repos, list_dir, read_file, write_file, delete_file, search_code",
                        "- run_action：action 传单个白名单名，如 build_gradle、test_gradle、start_gradle、build、stop_port；禁止任意 shell，禁止写成 build/build_gradle",
                        "- check_port：查看本机端口占用，返回监听进程 PID 和进程名",
                    ]
                )
            if stage == "CODING_PLAN":
                pass
            elif auto_test:
                parts.extend(
                    [
                        "自动化测试任务优先：先 list_dir/search_code/read_file 了解仓内测试结构；"
                        "在业务仓测试目录编写/修正用例（不要另起平行测试工程）；"
                        "用 run_action(action=test_gradle 或 test / test_maven / test_node / test_uv) 真正执行测试并依据输出写结论；"
                        "除非任务明确要求，不要改业务实现代码。",
                        "需要启动服务做验证时用 start_gradle 或 start_dev，再用 check_port 验证。"
                        "结束本会话启动的服务用 stop_dev。"
                        "端口被其他进程占用时，直接 run_action(action=stop_port, args={\"port\": \"端口号\"})，"
                        "服务端会用 netstat 找到监听进程并用 taskkill 结束；不要把 netstat 或 taskkill 交给用户手动执行。",
                        "完成启动服务测试后，一定要停掉服务再结束本轮：先 stop_dev，若 check_port 仍显示占用，再对同一端口执行 stop_port。"
                        "不得把本次测试启动的服务留在后台。",
                    ]
                )
            elif _is_int_test_fix_task(req):
                from dap_agent.config import settings

                cap = max(1, int(settings.issue_fix_max_list_dir))
                patched_keep = max(1, int(settings.issue_fix_patched_read_keep))
                parts.extend(
                    [
                        "存量集成测试修复两阶段：EXPLORE→立刻 write_file 改失败点；"
                        f"PATCHED→禁止 search/list，最多 {patched_keep} 次校验 read，然后结束。",
                        "以平台失败报告与人确认建议为准定点改码；"
                        f"list_dir 全会话最多 {cap} 次。禁止跑全量测试、禁止 write_output 报告（平台复测）。",
                        "成功态=至少一次 write_file；只聊天不算完成。",
                    ]
                )
            elif _is_issue_fix_task(req):
                from dap_agent.config import settings

                cap = max(1, int(settings.issue_fix_max_list_dir))
                patched_keep = max(1, int(settings.issue_fix_patched_read_keep))
                parts.extend(
                    [
                        "问题修复两阶段：EXPLORE→尽快 write_file；PATCHED→禁止 search/list，"
                        f"最多 {patched_keep} 次校验 read，立刻 write_output「问题修复报告.md」。",
                        "先读《问题原因分析》与问题单；按根因定点 search_code/read_file；"
                        f"list_dir 全会话最多 {cap} 次。search/read/list 均计入收窗，达 keep 硬拦。",
                        "改完一刀禁止再逛仓。需要启动服务时用 start_gradle/start_dev + check_port；"
                        "结束用 stop_dev/stop_port。",
                    ]
                )
            elif stage == "CODING_TASK":
                from dap_agent.config import settings

                cap = max(1, int(settings.coding_task_max_list_dir))
                result = deliverable or "task_result-*.json"
                parts.extend(
                    [
                        "单任务卡优先：按本卡 goal/touch 定点 search_code、read_file；"
                        "尽快 write_file 实现；可选 run_action(build/test) 校验；"
                        f"完成后 write_output「{result}」。",
                        f"list_dir 全会话最多 {cap} 次；禁止无目标逛仓、同范围反复 read；不要重写整仓。",
                        "需要启动服务时用 start_gradle 或 start_dev，再用 check_port 验证；"
                        "结束本会话启动的服务用 stop_dev / stop_port，勿留后台。",
                    ]
                )
            else:
                parts.extend(
                    [
                        "改代码前先 list_dir、search_code、read_file；改完用 run_action(action=build_gradle 或 build) 校验；"
                        "需要启动服务时用 start_gradle 或 start_dev，再用 check_port 验证。"
                        "结束本会话启动的服务用 stop_dev。"
                        "端口被其他进程占用时，直接 run_action(action=stop_port, args={\"port\": \"端口号\"})，"
                        "服务端会用 netstat 找到监听进程并用 taskkill 结束；不要把 netstat 或 taskkill 交给用户手动执行。",
                        "完成启动服务测试后，一定要停掉服务再结束本轮：先 stop_dev，若 check_port 仍显示占用，再对同一端口执行 stop_port。"
                        "不得把本次测试启动的服务留在后台。",
                    ]
                )
        if _needs_delivery_window(req):
            from dap_agent.config import settings

            keep = max(1, int(settings.context_evidence_read_keep_turns))
            if _is_review_task(req):
                parts.extend(
                    [
                        "",
                        "## 评审滚动窗（必须遵守）",
                        f"- read 原文保留约最近 {keep} 轮（与 context 压缩 keep 一致）；可连读以便看清交互。",
                        f"- 未结算 read 达到 {keep} 次前，必须结算本窗：write_output 窗口/分段评审"
                        "（有意见或明确无意见），或输出 need_human_confirm JSON。"
                        "无意见也算结算；禁止连续只读不评。",
                        "- 同范围勿反复 read；短间隔勿重复 list_dir（系统会回指针/冷却）。",
                        "- 结算后更早原文会被压缩为证据卡；然后开下一窗。",
                        "- 工具轮次打满会硬失败并中止，不会自动汇总交付物。",
                    ]
                )
            elif stage == "CODING_PLAN":
                cap = max(1, int(settings.coding_plan_max_list_dir))
                parts.extend(
                    [
                        "",
                        "## 任务拆分滚动窗（必须遵守）",
                        f"- 未结算 read/list 达到约 {keep} 次前，必须 write_output coding_plan.json。",
                        f"- list_dir 全阶段最多 {cap} 次；禁止 write_file；禁止空转逛仓。",
                        "- 宁早交粗糙任务卡，也不要读满轮次还不写。",
                        "- 工具轮次打满会硬失败并中止。",
                    ]
                )
            elif stage == "CODING_TASK":
                cap = max(1, int(settings.coding_task_max_list_dir))
                result = deliverable or "task_result-*.json"
                parts.extend(
                    [
                        "",
                        "## 单任务卡滚动窗（必须遵守）",
                        "- 成功态优先：尽快 write_file 出 diff；task_result 后补。",
                        f"- 未结算探读（read/list）达到约 {keep} 次前，必须 write_file 或 write_output「{result}」结算；"
                        "write_file 也算结算。禁止只探不改。",
                        f"- list_dir 全会话最多 {cap} 次；改动尽量落在 touch 范围。",
                        "- 工具轮次打满会硬失败并中止，不会自动汇总。",
                    ]
                )
            elif _is_int_test_fix_task(req):
                cap = max(1, int(settings.issue_fix_max_list_dir))
                patched_keep = max(1, int(settings.issue_fix_patched_read_keep))
                parts.extend(
                    [
                        "",
                        "## 存量测修复滚动窗（必须遵守）",
                        "- EXPLORE：search/read/list 均计入；未结算达 keep 硬拦，必须 write_file。",
                        f"- PATCHED：禁止 search/list；校验 read≤{patched_keep}，然后简述改动结束。",
                        "- 禁止跑全量测试；禁止 write_output 报告（平台检查 diff 后复测）。",
                        f"- list_dir≤{cap}；成功态=至少一次 write_file。",
                        "- 工具轮次打满硬失败。",
                    ]
                )
            elif _is_issue_fix_task(req):
                cap = max(1, int(settings.issue_fix_max_list_dir))
                patched_keep = max(1, int(settings.issue_fix_patched_read_keep))
                parts.extend(
                    [
                        "",
                        "## 问题修复滚动窗（必须遵守）",
                        "- EXPLORE：search/read/list 均计入；未结算达 keep 硬拦，必须 write_file。",
                        f"- PATCHED：禁止 search/list；校验 read≤{patched_keep}，"
                        "必须 write_output「问题修复报告.md」或对话 need_human_confirm JSON。",
                        "- 禁止 write_output「*确认*」；确认只能走对话 JSON。",
                        f"- list_dir≤{cap}；改完一刀禁止再逛仓；收尾短预算。",
                        "- 工具轮次打满硬失败，不自动汇总。",
                    ]
                )
            elif _is_issue_analysis_task(req):
                parts.extend(
                    [
                        "",
                        "## 问题分析滚动窗（必须遵守）",
                        "- 先读问题单，少搜早写；search/read/list 均计入收窗。",
                        f"- 未结算达 {keep} 次硬拦探索；必须 write_output「问题原因分析.md」"
                        "或对话 need_human_confirm。",
                        "- 禁止 write_output「*确认*.json」；短预算。",
                    ]
                )
            else:
                parts.extend(
                    [
                        "",
                        "## 文档产出滚动窗（必须遵守）",
                        "- 优先：少量 search + 针对性 read → 尽早 write_output 落盘大纲/目录或首章草稿，再按章扩写。",
                        f"- search/read/list 计入；未结算达 {keep} 次硬拦，必须 write_output"
                        "（大纲、章节草稿或阶段性要点均可）。禁止只探不写。",
                        "- 同 path/同范围勿反复 read；短间隔勿重复 list_dir（系统回指针且不刷新 keep）。",
                        "- 结算后更早原文会压缩为证据卡；工具轮次打满硬失败，不会自动汇总。",
                    ]
                )
        parts.append("材料索引：")
        parts.append(_file_index_text(idx))
    return "\n".join(parts)


def _user_content_with_images(text: str, images: list[Any] | None) -> Any:
    """正文保留【附图N】占位；每张图前再插同号标签，便于与文中位置对齐。"""
    body = text or ""
    if not images:
        return body
    parts: list[dict[str, Any]] = [{"type": "text", "text": body}]
    for img in images:
        url = getattr(img, "url", None) or (img.get("url") if isinstance(img, dict) else "") or ""
        if not url:
            continue
        label = getattr(img, "label", None) or (img.get("label") if isinstance(img, dict) else "") or ""
        alt = getattr(img, "alt", None) or (img.get("alt") if isinstance(img, dict) else "") or ""
        caption = f"【{label}】" if label else "【附图】"
        if alt:
            caption = f"{caption} {alt}"
        parts.append({"type": "text", "text": f"\n{caption}：\n"})
        parts.append({"type": "image_url", "image_url": {"url": url}})
    return parts if len(parts) > 1 else body


def to_lc_messages(req: ChatStreamRequest):
    messages = [SystemMessage(content=build_system_prompt(req))]
    for m in req.messages:
        if m.role == "user":
            messages.append(
                HumanMessage(content=_user_content_with_images(m.content or "", m.images))
            )
        elif m.role == "assistant":
            messages.append(AIMessage(content=m.content or ""))
        elif m.role == "system":
            messages.append(SystemMessage(content=m.content or ""))
    return messages


def _content_to_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                text = part.get("text") or ""
                if text:
                    parts.append(text)
            elif isinstance(part, str) and part:
                parts.append(part)
        return "".join(parts)
    return ""


_CAMEL_ALIASES = {
    "repoIndex": "repo_index",
    "relativePath": "relative_path",
    "startLine": "start_line",
    "endLine": "end_line",
    "nodeId": "node_id",
    "displayName": "display_name",
}


def _normalize_tool_args(name: str, raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        return {}
    args = dict(raw)
    for camel, snake in _CAMEL_ALIASES.items():
        if snake not in args and args.get(camel) is not None:
            args[snake] = args.pop(camel)
    if name == "read_docs":
        # 已由通用别名处理
        pass
    elif name == "write_output":
        pass
    return args


def _step(
    step: str,
    message: str,
    *,
    llm_round: int | None = None,
    detail: dict[str, Any] | None = None,
) -> StreamEvent:
    ev: StreamEvent = {"type": "step", "step": step, "message": message}
    if llm_round is not None:
        ev["llmRound"] = llm_round
    if detail is not None:
        ev["detail"] = detail
    return ev


def _truncate_detail_text(text: str, limit: int = 12000) -> str:
    if not text or len(text) <= limit:
        return text or ""
    return text[:limit] + "…(truncated)"


def _message_stats(messages: list) -> dict[str, Any]:
    by_role = {"system": 0, "human": 0, "ai": 0, "tool": 0, "other": 0}
    approx_chars = 0
    for m in messages:
        text = getattr(m, "content", None)
        if isinstance(text, str):
            approx_chars += len(text)
        elif text is not None:
            approx_chars += len(str(text))
        if isinstance(m, SystemMessage):
            by_role["system"] += 1
        elif isinstance(m, HumanMessage):
            by_role["human"] += 1
        elif isinstance(m, AIMessage):
            by_role["ai"] += 1
        elif isinstance(m, ToolMessage):
            by_role["tool"] += 1
        else:
            by_role["other"] += 1
    return {
        "messageCount": len(messages),
        "byRole": by_role,
        "approxChars": approx_chars,
        "estimatedTokens": max(1, int(approx_chars / 2)) if approx_chars else 0,
    }


def _msg_role(m: Any) -> str:
    if isinstance(m, SystemMessage):
        return "system"
    if isinstance(m, HumanMessage):
        return "human"
    if isinstance(m, AIMessage):
        return "ai"
    if isinstance(m, ToolMessage):
        return "tool"
    return type(m).__name__


def _msg_content_text(m: Any) -> str:
    c = getattr(m, "content", None)
    if c is None:
        return ""
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        bits: list[str] = []
        for part in c:
            if isinstance(part, str):
                bits.append(part)
            elif isinstance(part, dict) and part.get("type") == "text":
                bits.append(str(part.get("text") or ""))
        return "\n".join(bits)
    return str(c)


def _prompt_for_audit(messages: list) -> dict[str, Any]:
    """审计可见的「实际发给模型的 messages」快照（截断，避免撑爆 ES）。"""
    # 总量控制：system 多留一点，其余均分；整体约 60k 字符量级（服务端 detail 上限 80k）
    system_limit = 12000
    other_limit = 2500
    items: list[dict[str, Any]] = []
    total_chars = 0
    truncated_any = False
    for m in messages:
        role = _msg_role(m)
        raw = _msg_content_text(m)
        total_chars += len(raw)
        limit = system_limit if role == "system" else other_limit
        content = raw
        truncated = False
        if len(content) > limit:
            content = content[:limit] + f"…(truncated {len(raw) - limit} chars)"
            truncated = True
            truncated_any = True
        item: dict[str, Any] = {
            "role": role,
            "chars": len(raw),
            "truncated": truncated,
            "content": content,
        }
        if isinstance(m, AIMessage):
            tools = getattr(m, "tool_calls", None) or []
            if tools:
                item["toolCalls"] = [
                    {
                        "id": c.get("id"),
                        "name": c.get("name"),
                        "args": c.get("args"),
                    }
                    for c in tools
                    if isinstance(c, dict)
                ]
        if isinstance(m, ToolMessage):
            item["toolCallId"] = getattr(m, "tool_call_id", None)
            item["name"] = getattr(m, "name", None)
        items.append(item)
    return {
        "messageCount": len(items),
        "totalChars": total_chars,
        "truncated": truncated_any,
        "items": items,
    }


def _llm_detail(
    *,
    phase: str,
    gov: GovernResult | None = None,
    messages: list | None = None,
    round_no: int | None = None,
    max_rounds: int | None = None,
    tool_names: list[str] | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """审计日志用：调用大模型时带上治理信息 + 提示词快照。"""
    detail: dict[str, Any] = {"phase": phase}
    if round_no is not None:
        detail["round"] = round_no
    if max_rounds is not None:
        detail["maxRounds"] = max_rounds
    if messages is not None:
        detail["messages"] = _message_stats(messages)
        # 仅在真正「准备/拒发调用」时落提示词，避免 streaming/tool_calls 重复巨量存储
        if phase in {"call", "rejected", "summary", "timeout", "done"}:
            detail["prompt"] = _prompt_for_audit(messages)
    if gov is not None:
        detail["context"] = {
            "estimatedTokens": gov.estimated_tokens,
            "maxRoundTokens": gov.max_round_tokens,
            "downgradeLevel": gov.downgrade_level,
            "dialogMode": gov.dialog_mode,
            "templateLevel": gov.template_level,
            "rejected": bool(gov.rejected),
            "note": gov.note or None,
        }
    if tool_names:
        detail["toolCalls"] = tool_names
    if extra:
        detail.update(extra)
    return detail


def _content(delta: str) -> StreamEvent:
    return {"type": "content", "delta": delta}


def _tool_step_message(name: str, args: dict[str, Any], result: str) -> str:
    if name == "search_docs":
        q = args.get("query") or ""
        try:
            data = json.loads(result)
            hits = len(data.get("hits") or []) if isinstance(data, dict) else 0
            engine = data.get("engine") if isinstance(data, dict) else None
            extra = f"，引擎={engine}" if engine else ""
            return f"搜索「{q}」→ {hits} 条命中{extra}"
        except Exception:  # noqa: BLE001
            return f"搜索「{q}」"
    if name == "read_docs":
        path_hint = args.get("displayPath") or args.get("node_id") or args.get("nodeId") or "?"
        source = args.get("source") or "?"
        try:
            data = json.loads(result)
            if isinstance(data, dict):
                if data.get("error"):
                    return f"读取失败 {source}:{path_hint} — {data.get('error')}"
                display = data.get("displayPath") or path_hint
                return f"读取文件 {display}"
        except Exception:  # noqa: BLE001
            pass
        return f"读取文件 {source}:{path_hint}"
    if name == "write_output":
        try:
            data = json.loads(result)
            if isinstance(data, dict):
                if data.get("error"):
                    return f"写入失败 — {data.get('error')}"
                path = data.get("displayPath") or data.get("displayName") or args.get("display_name") or "?"
                action = "新建" if data.get("created") else "更新"
                return f"{action}输出文件 {path}"
        except Exception:  # noqa: BLE001
            pass
        return f"写入输出 {args.get('display_name') or args.get('displayName') or '文件'}"
    if name == "search_code":
        q = args.get("query") or ""
        try:
            data = json.loads(result)
            hits = len(data.get("hits") or []) if isinstance(data, dict) else 0
            return f"代码搜索「{q}」→ {hits} 条"
        except Exception:  # noqa: BLE001
            return f"代码搜索「{q}」"
    if name == "read_file":
        return f"读取代码 {args.get('relative_path') or args.get('relativePath') or '?'}"
    if name == "write_file":
        return f"写入代码 {args.get('relative_path') or args.get('relativePath') or '?'}"
    if name == "delete_file":
        return f"删除 {args.get('relative_path') or args.get('relativePath') or '?'}"
    if name == "list_dir":
        return f"列目录 {args.get('relative_path') or args.get('relativePath') or '.'}"
    if name == "run_action":
        act = args.get("action") or "?"
        try:
            data = json.loads(result)
            if isinstance(data, dict):
                if data.get("error"):
                    return f"动作 {act} 失败 — {data.get('error')}"
                code = data.get("exitCode")
                timed = data.get("timedOut")
                bg = data.get("background")
                extra = []
                if code is not None:
                    extra.append(f"exit={code}")
                if timed:
                    extra.append("超时")
                if bg:
                    extra.append(f"pid={data.get('pid')}")
                return f"动作 {act}" + (f"（{', '.join(extra)}）" if extra else "")
        except Exception:  # noqa: BLE001
            pass
        return f"动作 {act}"
    if name == "check_port":
        return f"检查端口 {args.get('port')}"
    if name == "list_code_repos":
        return "列出绑定仓库"
    return f"执行工具 {name}"


async def stream_reply(req: ChatStreamRequest) -> AsyncIterator[StreamEvent]:
    from dap_agent.config import settings

    if not (settings.llm_api_key or "").strip():
        raise RuntimeError("未配置 LLM_API_KEY，请在 dap-agent/.env 中填写后重启")

    messages = to_lc_messages(req)
    use_tools = bool(req.tools_enabled and req.session_id)
    tools: list = []
    if use_tools:
        sid = int(req.session_id)
        tools.extend(build_workspace_tools(sid))
        idx = _normalize_file_index(req.file_index)
        if idx and idx.codeRepos:
            tools.extend(build_code_repo_tools(sid))

    usage_acc: dict[str, int] = {
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "total_tokens": 0,
    }

    messages, gov0 = _apply_context_governor(req, messages)
    if gov0.rejected:
        yield _step(
            "llm",
            "上下文超限，已拒发模型生成",
            detail=_llm_detail(phase="rejected", gov=gov0, messages=messages),
        )
        yield _content(
            gov0.note
            or "CONTEXT_EXCEEDED：上下文压缩后仍超上限，请拆分任务或缩小材料后重试。"
        )
        yield _usage_event(usage_acc)
        return

    if not tools:
        yield _step(
            "llm",
            "正在生成回复…",
            detail=_llm_detail(phase="call", gov=gov0, messages=messages),
        )
        model = create_chat_model()
        last_chunk: Any = None
        try:
            async for chunk in model.astream(messages):
                last_chunk = chunk
                text = _content_to_text(chunk.content)
                if text:
                    yield _content(text)
        except Exception as exc:  # noqa: BLE001
            if _is_stream_chunk_timeout(exc):
                logger.warning("llm stream chunk timeout (no tools): %s", exc)
                yield _content(
                    "\n\n（模型流式输出长时间无新内容，本轮已中断。可发送「继续」接着做。）"
                )
                yield _step(
                    "llm",
                    "模型响应超时，已结束",
                    detail=_llm_detail(
                        phase="timeout",
                        gov=gov0,
                        messages=messages,
                        extra={"reason": "stream_chunk_timeout"},
                    ),
                )
                if last_chunk is not None:
                    _add_usage(usage_acc, last_chunk)
                yield _usage_event(usage_acc)
                return
            raise
        if last_chunk is not None:
            _add_usage(usage_acc, last_chunk)
        yield _step(
            "llm",
            "已结束",
            detail=_llm_detail(phase="done", gov=gov0, messages=messages),
        )
        yield _usage_event(usage_acc)
        return

    model = create_chat_model()
    model_with_tools = model.bind_tools(tools)
    tool_map = {t.name: t for t in tools}
    max_rounds = _max_tool_rounds(req)
    from dap_agent.config import settings as _round_settings

    issue_fix_wrap = max(1, int(_round_settings.max_tool_rounds_issue_fix_wrap))
    issue_fix_total = max(1, int(_round_settings.max_tool_rounds_issue_fix))
    wrap_applied = False
    logger.info(
        "tool rounds cap=%s run_id=%s session_type=%s task=%s",
        max_rounds,
        req.run_id,
        req.session_type,
        _task_type_code(req) or "-",
    )

    round_no = 0
    while round_no < max_rounds:
        round_no += 1
        # 评审/交付：收窗前提醒（硬拦在 short_circuit）
        messages = _maybe_inject_review_window_nudge(req, messages)
        # 先治理再记账：避免「正在调用」后立刻拒发，且 detail 带上真实 token
        messages, gov_r = _apply_context_governor(req, messages)
        if gov_r.rejected:
            yield _step(
                "llm",
                "上下文超限，已拒发模型生成",
                llm_round=round_no,
                detail=_llm_detail(
                    phase="rejected",
                    gov=gov_r,
                    messages=messages,
                    round_no=round_no,
                    max_rounds=max_rounds,
                ),
            )
            yield _content(
                gov_r.note
                or "CONTEXT_EXCEEDED：上下文压缩后仍超上限，请拆分任务或缩小材料后重试。"
            )
            yield _usage_event(usage_acc)
            return

        yield _step(
            "llm",
            f"正在调用模型（第 {round_no}/{max_rounds} 轮）…",
            llm_round=round_no,
            detail=_llm_detail(
                phase="call",
                gov=gov_r,
                messages=messages,
                round_no=round_no,
                max_rounds=max_rounds,
            ),
        )
        # 给事件循环一点时间，先把步骤推到前端
        await asyncio.sleep(0)

        accumulated: AIMessageChunk | None = None
        streamed_any = False
        try:
            async for chunk in model_with_tools.astream(messages):
                text = _content_to_text(getattr(chunk, "content", None))
                if text:
                    if not streamed_any:
                        yield _step(
                            "llm",
                            "正在输出回复…",
                            llm_round=round_no,
                            detail=_llm_detail(
                                phase="streaming",
                                gov=gov_r,
                                round_no=round_no,
                                max_rounds=max_rounds,
                            ),
                        )
                        streamed_any = True
                    yield _content(text)
                accumulated = chunk if accumulated is None else accumulated + chunk
        except Exception as exc:  # noqa: BLE001
            if _is_stream_chunk_timeout(exc):
                logger.warning(
                    "llm stream chunk timeout round=%s/%s run_id=%s: %s",
                    round_no,
                    max_rounds,
                    req.run_id,
                    exc,
                )
                yield _content(
                    "\n\n（模型流式输出长时间无新内容，本轮已中断。可发送「继续」接着做。）"
                )
                yield _step(
                    "llm",
                    "模型响应超时，已结束",
                    llm_round=round_no,
                    detail=_llm_detail(
                        phase="timeout",
                        gov=gov_r,
                        messages=messages,
                        round_no=round_no,
                        max_rounds=max_rounds,
                        extra={"reason": "stream_chunk_timeout"},
                    ),
                )
                return
            raise

        if accumulated is None:
            yield _step(
                "llm",
                "已结束",
                llm_round=round_no,
                detail=_llm_detail(
                    phase="done",
                    gov=gov_r,
                    messages=messages,
                    round_no=round_no,
                    max_rounds=max_rounds,
                    extra={"emptyResponse": True},
                ),
            )
            yield _usage_event(usage_acc)
            return

        _add_usage(usage_acc, accumulated)

        tool_calls = getattr(accumulated, "tool_calls", None) or []
        if not tool_calls:
            # astream 已推送正文；若模型一次返回整段（无增量），再假分片兜底
            if not streamed_any:
                yield _step(
                    "llm",
                    "正在输出回复…",
                    llm_round=round_no,
                    detail=_llm_detail(
                        phase="streaming",
                        gov=gov_r,
                        round_no=round_no,
                        max_rounds=max_rounds,
                    ),
                )
                text = _content_to_text(getattr(accumulated, "content", None))
                if text:
                    step = 24
                    for i in range(0, len(text), step):
                        yield _content(text[i : i + step])
                        await asyncio.sleep(_CONTENT_CHUNK_SLEEP)
            yield _step(
                "llm",
                "已结束",
                llm_round=round_no,
                detail=_llm_detail(
                    phase="done",
                    gov=gov_r,
                    messages=messages,
                    round_no=round_no,
                    max_rounds=max_rounds,
                ),
            )
            yield _usage_event(usage_acc)
            return

        # 本轮是工具调用：若误流过正文，前端已显示；历史直接挂累计后的 AI 消息
        messages.append(accumulated)
        names = [str(c.get("name") or "") for c in tool_calls]
        logger.info(
            "tool round=%s/%s run_id=%s calls=%s",
            round_no,
            max_rounds,
            req.run_id,
            names,
        )
        yield _step(
            "llm",
            f"模型请求工具：{', '.join(names) or '—'}",
            llm_round=round_no,
            detail=_llm_detail(
                phase="tool_calls",
                gov=gov_r,
                round_no=round_no,
                max_rounds=max_rounds,
                tool_names=names,
            ),
        )

        for call in tool_calls:
            name = call.get("name") or ""
            call_id = call.get("id") or name
            args = _normalize_tool_args(name, call.get("args"))
            tool = tool_map.get(name)

            if name == "search_docs":
                yield _step(
                    "search_docs",
                    f"搜索「{args.get('query') or ''}」…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            elif name == "read_docs":
                yield _step(
                    "read_docs",
                    f"读取文件 {args.get('source') or '?'}:{args.get('node_id') or args.get('nodeId') or '…'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            elif name == "write_output":
                yield _step(
                    "write_output",
                    f"写入输出 {args.get('display_name') or args.get('displayName') or '文件'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": {k: v for k, v in args.items() if k != "content"}},
                )
            elif name == "search_code":
                yield _step(
                    "search_code",
                    f"代码搜索「{args.get('query') or ''}」…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            elif name == "read_file":
                yield _step(
                    "read_file",
                    f"读取代码 {args.get('relative_path') or args.get('relativePath') or '…'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            elif name == "write_file":
                yield _step(
                    "write_file",
                    f"写入代码 {args.get('relative_path') or args.get('relativePath') or '…'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": {k: v for k, v in args.items() if k != "content"}},
                )
            elif name == "run_action":
                yield _step(
                    "run_action",
                    f"执行动作 {args.get('action') or '…'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            elif name == "check_port":
                yield _step(
                    "check_port",
                    f"检查端口 {args.get('port') or '…'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            else:
                yield _step(
                    name or "tool",
                    f"执行 {name or '工具'}…",
                    llm_round=round_no,
                    detail={"phase": "start", "args": args},
                )
            await asyncio.sleep(0)

            _need_args = {
                "read_docs",
                "write_output",
                "search_docs",
                "read_file",
                "write_file",
                "delete_file",
                "search_code",
                "run_action",
                "check_port",
            }
            try:
                if tool is None:
                    result = json.dumps({"error": f"未知工具: {name}"}, ensure_ascii=False)
                elif not args and name in _need_args:
                    result = json.dumps(
                        {
                            "error": (
                                f"工具 {name} 缺少参数。"
                                "read_docs 需要 source+node_id；"
                                "search_docs/search_code 需要 query；"
                                "write_output 需要 display_name+content；"
                                "read_file/write_file/delete_file 需要 relative_path；"
                                "run_action 需要 action；check_port 需要 port。"
                            )
                        },
                        ensure_ascii=False,
                    )
                else:
                    stub = _maybe_short_circuit_tool(name, args, messages, req=req)
                    if stub is not None:
                        result = stub
                    else:
                        result = await tool.ainvoke(args)
                        if not isinstance(result, str):
                            result = json.dumps(result, ensure_ascii=False, default=str)
            except Exception as exc:  # noqa: BLE001
                logger.warning("tool %s failed: %s | args=%s", name, exc, args)
                result = json.dumps({"error": str(exc)}, ensure_ascii=False)

            yield _step(
                name or "tool",
                _tool_step_message(name, args, result),
                llm_round=round_no,
                detail={
                    "phase": "done",
                    "args": args if name not in {"write_output", "write_file"} else {
                        k: v for k, v in args.items() if k != "content"
                    },
                    "result": _truncate_detail_text(result),
                },
            )
            tm = ToolMessage(content=result, tool_call_id=call_id)
            try:
                if name:
                    tm.name = name  # type: ignore[attr-defined]
            except Exception:  # noqa: BLE001
                pass
            messages.append(tm)

        # 问题修复 / 存量测修复：首次成功 write_file → PATCHED，放开收尾 wrap 预算并收紧上限
        if (
            _is_code_patch_task(req)
            and not wrap_applied
            and _issue_fix_phase(messages) == "PATCHED"
        ):
            wrap_applied = True
            new_cap = min(issue_fix_total, round_no + issue_fix_wrap)
            if new_cap > max_rounds:
                logger.info(
                    "code_patch PATCHED at round=%s expand cap %s→%s run_id=%s",
                    round_no,
                    max_rounds,
                    new_cap,
                    req.run_id,
                )
            else:
                logger.info(
                    "code_patch PATCHED at round=%s shrink/keep cap %s→%s run_id=%s",
                    round_no,
                    max_rounds,
                    new_cap,
                    req.run_id,
                )
            max_rounds = max(round_no, new_cap)

    # 打满上限：硬失败，禁止再调模型「汇总」（避免假完成、人不知情）
    messages, gov_s = _apply_context_governor(req, messages)
    fail_note = (
        f"\n\nTOOL_ROUNDS_EXCEEDED：工具轮次已用尽（{max_rounds}），任务未完成，已中止。"
        "本次不会自动汇总或写交付物。请查看审计步骤与中间产出后重新执行，或拆分任务后再跑。"
    )
    if gov_s.rejected:
        fail_note = (
            "\n\n"
            + (
                gov_s.note
                or "CONTEXT_EXCEEDED：已达工具轮次上限且上下文仍超限。"
            )
            + " 任务未完成，已中止；不会自动汇总交付物。"
        )
    yield _content(fail_note)
    yield _step(
        "llm",
        f"已达工具轮次上限（{max_rounds}），已中止（不汇总）",
        llm_round=max_rounds,
        detail=_llm_detail(
            phase="failed",
            gov=gov_s,
            messages=messages,
            round_no=max_rounds,
            max_rounds=max_rounds,
            extra={"reason": "tool_rounds_cap", "hardFail": True},
        ),
    )
    yield _usage_event(usage_acc)
