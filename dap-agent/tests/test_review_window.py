# -*- coding: utf-8 -*-
"""交付/评审滚动窗：未结算 read 计数、收窗提醒、读指针与 list_dir 冷却。"""
from __future__ import annotations

import json

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from dap_agent.agents.workbench_agent import (
    _DELIVERY_HARD_BLOCK_MARK,
    _DELIVERY_WINDOW_NUDGE_MARK,
    _LIST_DIR_COOLDOWN_MARK,
    _READ_POINTER_MARK,
    _REVIEW_WINDOW_NUDGE_MARK,
    _delivery_hard_block_explore,
    _issue_fix_phase,
    _maybe_inject_review_window_nudge,
    _maybe_short_circuit_tool,
    _needs_delivery_window,
    _unsettled_explore_count,
    _unsettled_read_count,
)
from dap_agent.schemas.chat import ChatStreamRequest, FileIndex, PositionInfo


def _req_review() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-review",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="产品"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="REVIEW_UI",
            taskTypeName="UI设计评审-产品",
            files=[],
        ),
    )


def _req_gen_detail() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-gen",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="研发"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="GEN_DETAIL",
            taskTypeName="编写详细设计",
            files=[],
        ),
    )


def _req_coding() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-code",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="研发"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="CODING",
            taskTypeName="编码",
            files=[],
            codeRepos=["E:/repo"],
        ),
    )


def _read_tm(cid: str, node_id: int = 229, start: int = 1, end: int = 40) -> ToolMessage:
    body = {
        "source": "wiki",
        "nodeId": node_id,
        "displayPath": "UI设计.html",
        "content": "x" * 20,
        "startLine": start,
        "endLine": end,
    }
    tm = ToolMessage(content=json.dumps(body, ensure_ascii=False), tool_call_id=cid)
    tm.name = "read_docs"  # type: ignore[attr-defined]
    return tm


def _write_tm(cid: str) -> ToolMessage:
    tm = ToolMessage(
        content=json.dumps({"created": True, "displayName": "draft.md"}, ensure_ascii=False),
        tool_call_id=cid,
    )
    tm.name = "write_output"  # type: ignore[attr-defined]
    return tm


def _ai_read(cid: str, node_id: int = 229, start: int = 1, end: int = 40) -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[
            {
                "name": "read_docs",
                "id": cid,
                "args": {
                    "source": "wiki",
                    "node_id": node_id,
                    "start_line": start,
                    "end_line": end,
                },
            }
        ],
    )


def _ai_list_dir(cid: str, path: str = ".") -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[
            {
                "name": "list_dir",
                "id": cid,
                "args": {"relative_path": path},
            }
        ],
    )


def test_unsettled_read_count_resets_on_write() -> None:
    msgs = [
        _read_tm("r1"),
        _read_tm("r2"),
        _write_tm("w1"),
        _read_tm("r3"),
    ]
    assert _unsettled_read_count(msgs) == 1


def test_unsettled_ignores_evidence_cards() -> None:
    card = ToolMessage(
        content="[阅读证据] file=wiki:229 …",
        tool_call_id="c1",
    )
    card.name = "read_docs"  # type: ignore[attr-defined]
    msgs = [card, _read_tm("r1")]
    assert _unsettled_read_count(msgs) == 1


def test_nudge_injected_for_review_at_keep() -> None:
    req = _req_review()
    msgs = [_read_tm(f"r{i}") for i in range(5)]
    out = _maybe_inject_review_window_nudge(req, msgs)
    assert len(out) == 6
    assert isinstance(out[-1], HumanMessage)
    assert _REVIEW_WINDOW_NUDGE_MARK in str(out[-1].content)


def test_nudge_injected_for_gen_detail() -> None:
    assert _needs_delivery_window(_req_gen_detail())
    req = _req_gen_detail()
    msgs = [_read_tm(f"r{i}") for i in range(5)]
    out = _maybe_inject_review_window_nudge(req, msgs)
    assert isinstance(out[-1], HumanMessage)
    assert _DELIVERY_WINDOW_NUDGE_MARK in str(out[-1].content)


def _req_coding_plan() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-plan",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="研发"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="GEN_CODING",
            taskTypeName="开发编码",
            codingStage="CODING_PLAN",
            stageDeliverableName="coding_plan.json",
            files=[],
            codeRepos=["E:/repo"],
        ),
    )


def test_nudge_injected_for_coding_plan() -> None:
    from dap_agent.agents.workbench_agent import _max_tool_rounds, _needs_delivery_window

    req = _req_coding_plan()
    assert _needs_delivery_window(req)
    assert _max_tool_rounds(req) <= 32
    msgs = [_read_tm(f"r{i}") for i in range(5)]
    out = _maybe_inject_review_window_nudge(req, msgs)
    assert isinstance(out[-1], HumanMessage)
    assert "coding_plan.json" in str(out[-1].content)


def _req_coding_task() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-ctask",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="研发"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="GEN_CODING",
            taskTypeName="开发编码",
            codingStage="CODING_TASK",
            codingTaskId="T01",
            stageDeliverableName="task_result-T01.json",
            files=[],
            codeRepos=["E:/repo"],
        ),
    )


def test_nudge_injected_for_coding_task() -> None:
    from dap_agent.agents.workbench_agent import _max_tool_rounds

    req = _req_coding_task()
    assert _needs_delivery_window(req)
    assert _max_tool_rounds(req) <= 96
    msgs = [_read_file_tm(f"r{i}") for i in range(5)]
    out = _maybe_inject_review_window_nudge(req, msgs)
    assert isinstance(out[-1], HumanMessage)
    assert _DELIVERY_WINDOW_NUDGE_MARK in str(out[-1].content)
    assert "write_file" in str(out[-1].content)


def test_coding_task_write_file_settles() -> None:
    msgs = [
        _read_file_tm("r1"),
        _read_file_tm("r2"),
        _write_file_tm("w1"),
        _read_file_tm("r3"),
    ]
    assert _unsettled_read_count(msgs, write_file_settles=True) == 1


def test_coding_task_list_dir_cap() -> None:
    from dap_agent.config import settings

    req = _req_coding_task()
    cap = max(1, int(settings.coding_task_max_list_dir))
    msgs: list = []
    for i in range(cap):
        # 穿插 write_file 结算，避免先被收窗硬拦
        if i > 0 and i % 4 == 0:
            msgs.append(_write_file_tm(f"w{i}"))
        listed = ToolMessage(
            content=json.dumps({"entries": ["a"]}, ensure_ascii=False), tool_call_id=f"l{i}"
        )
        listed.name = "list_dir"  # type: ignore[attr-defined]
        msgs.extend([_ai_list_dir(f"l{i}", f"p{i}"), listed])
    stub = _maybe_short_circuit_tool("list_dir", {"relative_path": "new"}, msgs, req=req)
    assert stub is not None
    assert stub.startswith(_LIST_DIR_COOLDOWN_MARK) or stub.startswith(_DELIVERY_HARD_BLOCK_MARK)
    assert "任务卡" in stub or "硬拦" in stub


def test_nudge_not_for_coding() -> None:
    req = _req_coding()
    msgs = [_read_tm(f"r{i}") for i in range(5)]
    out = _maybe_inject_review_window_nudge(req, msgs)
    assert out is msgs or out == msgs


def _req_issue_fix() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-fix",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="研发"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="GEN_ISSUE_FIX",
            taskTypeName="问题修复",
            files=[],
            codeRepos=["E:/repo"],
        ),
    )


def _write_file_tm(cid: str) -> ToolMessage:
    tm = ToolMessage(
        content=json.dumps({"ok": True, "relativePath": "a.java"}, ensure_ascii=False),
        tool_call_id=cid,
    )
    tm.name = "write_file"  # type: ignore[attr-defined]
    return tm


def _read_file_tm(cid: str) -> ToolMessage:
    body = {
        "relativePath": "src/Main.java",
        "content": "class Main {}",
        "startLine": 1,
        "endLine": 10,
    }
    tm = ToolMessage(content=json.dumps(body, ensure_ascii=False), tool_call_id=cid)
    tm.name = "read_file"  # type: ignore[attr-defined]
    return tm


def test_nudge_injected_for_issue_fix() -> None:
    from dap_agent.agents.workbench_agent import _max_tool_rounds

    req = _req_issue_fix()
    assert _needs_delivery_window(req)
    assert _max_tool_rounds(req) <= 40
    msgs = [_read_file_tm(f"r{i}") for i in range(5)]
    out = _maybe_inject_review_window_nudge(req, msgs)
    assert isinstance(out[-1], HumanMessage)
    assert _DELIVERY_WINDOW_NUDGE_MARK in str(out[-1].content)
    assert "write_file" in str(out[-1].content)


def test_issue_fix_write_file_settles() -> None:
    msgs = [
        _read_file_tm("r1"),
        _read_file_tm("r2"),
        _write_file_tm("w1"),
        _read_file_tm("r3"),
    ]
    assert _unsettled_read_count(msgs, write_file_settles=True) == 1
    assert _unsettled_read_count(msgs, write_file_settles=False) == 3
    assert _issue_fix_phase(msgs) == "PATCHED"


def test_issue_fix_hard_block_explore_and_patched() -> None:
    req = _req_issue_fix()
    explore_msgs = [_read_file_tm(f"r{i}") for i in range(5)]
    block = _delivery_hard_block_explore(req, "search_code", explore_msgs)
    assert block is not None
    assert block.startswith(_DELIVERY_HARD_BLOCK_MARK)

    patched = [_write_file_tm("w1"), _read_file_tm("a"), _read_file_tm("b"), _read_file_tm("c")]
    assert _issue_fix_phase(patched) == "PATCHED"
    assert _delivery_hard_block_explore(req, "search_code", patched) is not None
    assert _delivery_hard_block_explore(req, "list_dir", patched) is not None
    assert _delivery_hard_block_explore(req, "read_file", patched) is not None


def test_search_counts_toward_unsettled() -> None:
    def _search_tm(cid: str) -> ToolMessage:
        tm = ToolMessage(content=json.dumps({"hits": []}, ensure_ascii=False), tool_call_id=cid)
        tm.name = "search_code"  # type: ignore[attr-defined]
        return tm

    msgs = [_search_tm("s1"), _search_tm("s2"), _read_file_tm("r1")]
    assert _unsettled_explore_count(msgs, write_file_settles=True) == 3


def _req_issue_analysis() -> ChatStreamRequest:
    return ChatStreamRequest(
        run_id="t-analysis",
        session_id=1,
        session_type="TASK",
        position=PositionInfo(name="研发"),
        messages=[],
        tools_enabled=True,
        file_index=FileIndex(
            taskTypeCode="GEN_ISSUE_ANALYSIS",
            taskTypeName="问题分析",
            files=[],
        ),
    )


def test_issue_analysis_short_budget_and_hard_block() -> None:
    from dap_agent.agents.workbench_agent import _max_tool_rounds

    req = _req_issue_analysis()
    assert _needs_delivery_window(req)
    assert _max_tool_rounds(req) <= 24
    msgs = [_read_tm(f"r{i}") for i in range(5)]
    assert _delivery_hard_block_explore(req, "search_docs", msgs) is not None


def test_issue_fix_list_dir_cap() -> None:
    from dap_agent.config import settings

    req = _req_issue_fix()
    cap = max(1, int(settings.issue_fix_max_list_dir))
    msgs: list = []
    for i in range(cap):
        if i > 0 and i % 4 == 0:
            msgs.append(_write_file_tm(f"w{i}"))
        listed = ToolMessage(
            content=json.dumps({"entries": ["a"]}, ensure_ascii=False), tool_call_id=f"l{i}"
        )
        listed.name = "list_dir"  # type: ignore[attr-defined]
        msgs.extend([_ai_list_dir(f"l{i}", f"p{i}"), listed])
    stub = _maybe_short_circuit_tool("list_dir", {"relative_path": "new"}, msgs, req=req)
    assert stub is not None
    # 达 cap 或 PATCHED 后硬拦 list 均可
    assert stub.startswith(_LIST_DIR_COOLDOWN_MARK) or stub.startswith(_DELIVERY_HARD_BLOCK_MARK)


def test_nudge_deduped() -> None:
    req = _req_review()
    msgs = [_read_tm(f"r{i}") for i in range(5)]
    once = _maybe_inject_review_window_nudge(req, msgs)
    twice = _maybe_inject_review_window_nudge(req, once)
    assert sum(
        1
        for m in twice
        if isinstance(m, HumanMessage) and _REVIEW_WINDOW_NUDGE_MARK in str(m.content)
    ) == 1


def test_settlement_via_confirm_json() -> None:
    msgs = [
        _read_tm("r1"),
        _read_tm("r2"),
        AIMessage(content='```json\n{"phase":"need_human_confirm","findings":[]}\n```'),
        _read_tm("r3"),
    ]
    assert _unsettled_read_count(msgs) == 1


def test_short_circuit_same_range_read() -> None:
    args = {
        "source": "wiki",
        "node_id": 229,
        "start_line": 1,
        "end_line": 40,
    }
    msgs = [
        _ai_read("r1", start=1, end=40),
        _read_tm("r1", start=1, end=40),
    ]
    stub = _maybe_short_circuit_tool("read_docs", args, msgs)
    assert stub is not None
    assert stub.startswith(_READ_POINTER_MARK)


def test_short_circuit_allows_new_range() -> None:
    msgs = [
        _ai_read("r1", start=1, end=40),
        _read_tm("r1", start=1, end=40),
    ]
    stub = _maybe_short_circuit_tool(
        "read_docs",
        {"source": "wiki", "node_id": 229, "start_line": 41, "end_line": 80},
        msgs,
    )
    assert stub is None


def test_list_dir_cooldown() -> None:
    listed = ToolMessage(content=json.dumps({"entries": ["a"]}, ensure_ascii=False), tool_call_id="l1")
    listed.name = "list_dir"  # type: ignore[attr-defined]
    msgs = [_ai_list_dir("l1", "."), listed]
    stub = _maybe_short_circuit_tool("list_dir", {"relative_path": "."}, msgs)
    assert stub is not None
    assert stub.startswith(_LIST_DIR_COOLDOWN_MARK)


def test_unsettled_ignores_read_pointer() -> None:
    ptr = ToolMessage(content=f"{_READ_POINTER_MARK} key=x", tool_call_id="p1")
    ptr.name = "read_docs"  # type: ignore[attr-defined]
    msgs = [_read_tm("r1"), ptr]
    assert _unsettled_read_count(msgs) == 1


if __name__ == "__main__":
    test_unsettled_read_count_resets_on_write()
    test_unsettled_ignores_evidence_cards()
    test_nudge_injected_for_review_at_keep()
    test_nudge_injected_for_gen_detail()
    test_nudge_injected_for_coding_plan()
    test_nudge_injected_for_coding_task()
    test_coding_task_write_file_settles()
    test_coding_task_list_dir_cap()
    test_nudge_not_for_coding()
    test_nudge_injected_for_issue_fix()
    test_issue_fix_write_file_settles()
    test_issue_fix_hard_block_explore_and_patched()
    test_search_counts_toward_unsettled()
    test_issue_analysis_short_budget_and_hard_block()
    test_issue_fix_list_dir_cap()
    test_nudge_deduped()
    test_settlement_via_confirm_json()
    test_short_circuit_same_range_read()
    test_short_circuit_allows_new_range()
    test_list_dir_cooldown()
    test_unsettled_ignores_read_pointer()
    print("OK review window")
