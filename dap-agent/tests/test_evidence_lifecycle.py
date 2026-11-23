"""P0 证据卡验收：search 索引卡 / 续读合并 / 过期阅读卡。"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from dap_agent.context.governor import (
    ContextGovernor,
    _apply_evidence_lifecycle,
    _msg_text,
)


def _ai_tools(cid_name_args: list[tuple[str, str, dict]]) -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[
            {"id": cid, "name": name, "args": args} for cid, name, args in cid_name_args
        ],
    )


def _tool(cid: str, name: str, payload: dict) -> ToolMessage:
    tm = ToolMessage(content=json.dumps(payload, ensure_ascii=False), tool_call_id=cid)
    tm.name = name
    return tm


def test_search_to_index_card() -> None:
    hits = [
        {"relativePath": "a/Book.java", "line": 12, "snippet": "availableCopies"},
        {"relativePath": "a/Book.java", "line": 48, "snippet": "getAvailable"},
        {"relativePath": "b/List.vue", "line": 47, "text": "availableCopies"},
    ]
    dialog = [
        HumanMessage(content="go"),
        _ai_tools([("s1", "search_code", {"query": "availableCopies"})]),
        _tool(
            "s1",
            "search_code",
            {"repoPath": "E:/repo", "engine": "line-scan", "hits": hits},
        ),
        # 下一 LLM 轮：此时上一轮 search 已被「看过」，应收成索引卡
        _ai_tools([("r1", "read_file", {"relative_path": "a/Book.java"})]),
        _tool(
            "r1",
            "read_file",
            {"relativePath": "a/Book.java", "content": "class Book {}", "startLine": 1, "endLine": 20},
        ),
        AIMessage(content="ok"),
    ]
    out = _apply_evidence_lifecycle(dialog, fresh_search_turns=1, read_keep_turns=2)
    search_msg = _msg_text(out[2])
    assert search_msg.startswith("[搜索证据]"), search_msg[:80]
    assert "availableCopies" in search_msg
    assert "Book.java" in search_msg
    assert '"hits"' not in search_msg
    assert "原始命中列表已折叠" in search_msg
    # 最近一轮的 read 仍在
    assert "class Book" in _msg_text(out[4])
    print("OK search→索引卡")


def test_read_merge_in_keep_window() -> None:
    dialog = [
        HumanMessage(content="go"),
        # turn0
        _ai_tools([("a", "read_docs", {"source": "wiki", "node_id": 224, "start_line": 1, "end_line": 10})]),
        _tool(
            "a",
            "read_docs",
            {
                "source": "wiki",
                "nodeId": 224,
                "displayPath": "PRD.md",
                "startLine": 1,
                "endLine": 10,
                "content": "# title\nline1-10 body",
            },
        ),
        # turn1 续读
        _ai_tools([("b", "read_docs", {"source": "wiki", "node_id": 224, "start_line": 10, "end_line": 20})]),
        _tool(
            "b",
            "read_docs",
            {
                "source": "wiki",
                "nodeId": 224,
                "displayPath": "PRD.md",
                "startLine": 10,
                "endLine": 20,
                "content": "line10-20 body",
            },
        ),
        AIMessage(content="thinking"),
    ]
    out = _apply_evidence_lifecycle(dialog, fresh_search_turns=1, read_keep_turns=2)
    first = _msg_text(out[2])
    second = _msg_text(out[4])
    assert first.startswith("[read merged]"), first[:100]
    assert "line10-20" in second
    assert "line1-10" in second  # 合并进最新
    assert "<<<lines 1-10>>>" in second
    print("OK 同文件续读合并")


def test_read_expire_to_evidence_card() -> None:
    dialog = [
        HumanMessage(content="go"),
        _ai_tools([("a", "read_file", {"relative_path": "Foo.java", "start_line": 1, "end_line": 10})]),
        _tool(
            "a",
            "read_file",
            {
                "relativePath": "Foo.java",
                "startLine": 1,
                "endLine": 10,
                "content": "package x;\nclass Foo { // important }",
            },
        ),
        # 后续 2 轮不再碰该文件（keep=2 时，再加 2 轮后过期：turn0 在 n=3 时 cutoff=1，过期）
        _ai_tools([("b", "search_code", {"query": "bar"})]),
        _tool("b", "search_code", {"repoPath": "/r", "engine": "x", "hits": []}),
        _ai_tools([("c", "search_code", {"query": "baz"})]),
        _tool("c", "search_code", {"repoPath": "/r", "engine": "x", "hits": []}),
        AIMessage(content="done"),
    ]
    # turns: 0 read, 1 search, 2 search, 3 final ai-only → n=4, read_cutoff=2, last read ti=0 < 2 → 证据卡
    out = _apply_evidence_lifecycle(dialog, fresh_search_turns=1, read_keep_turns=2)
    read_msg = _msg_text(out[2])
    assert read_msg.startswith("[阅读证据]"), read_msg[:120]
    assert "Foo.java" in read_msg
    assert "important" in read_msg or "Foo" in read_msg
    assert "package x" not in read_msg or "要点" in read_msg
    print("OK 过期 read→证据卡")


def test_govern_still_runs() -> None:
    big = "x" * 800
    hits = [{"relativePath": f"F{i}.java", "line": i, "snippet": big[:40]} for i in range(20)]
    msgs: list = [SystemMessage(content="sys " + "y" * 200), HumanMessage(content="go")]
    # round 0 search
    msgs.append(_ai_tools([("s0", "search_code", {"query": "q"})]))
    msgs.append(
        _tool("s0", "search_code", {"repoPath": "/r", "engine": "line-scan", "hits": hits})
    )
    # rounds 1..5 read different files
    for i in range(1, 6):
        msgs.append(
            _ai_tools([("r" + str(i), "read_file", {"relative_path": f"F{i}.java"})])
        )
        msgs.append(
            _tool(
                "r" + str(i),
                "read_file",
                {"relativePath": f"F{i}.java", "content": big, "startLine": 1, "endLine": 50},
            )
        )
    msgs.append(AIMessage(content="final"))
    g = ContextGovernor(
        max_round_tokens=8000,
        soft_ratio=0.2,
        hard_ratio=0.4,
        template_ratio=0.5,
        sliding_k=3,
        evidence_fresh_search_turns=1,
        evidence_read_keep_turns=2,
    )
    r = g.govern(msgs)
    texts = [_msg_text(m) for m in r.messages if hasattr(m, "content")]
    joined = "\n".join(texts)
    # 证据卡可能再被 L1/L2 摘要；但原始大 hits 不应残留
    assert joined.count('"snippet"') < 5, joined.count('"snippet"')
    assert '"hits": [' not in joined
    assert r.downgrade_level in {
        "none",
        "L1_summarize",
        "L2_sliding",
        "L3_template",
        "L4_reject",
    }
    print(
        "OK govern",
        "downgrade=",
        r.downgrade_level,
        "tokens≈",
        r.estimated_tokens,
        "rejected=",
        r.rejected,
    )


if __name__ == "__main__":
    test_search_to_index_card()
    test_read_merge_in_keep_window()
    test_read_expire_to_evidence_card()
    test_govern_still_runs()
    print("ALL PASSED")
