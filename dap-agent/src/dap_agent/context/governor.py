"""上下文二分法治理：System 模板降级；对话桶（user/assistant/tool）摘要 + 滑动 K。"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)


class ContextExceededError(RuntimeError):
    """压缩与极限瘦身后仍超硬顶，拒发业务 LLM。"""

    def __init__(self, message: str, *, estimated_tokens: int, limit: int):
        super().__init__(message)
        self.estimated_tokens = estimated_tokens
        self.limit = limit


@dataclass
class GovernResult:
    messages: list[BaseMessage]
    template_level: str = "full"  # full | outline | skeleton
    dialog_mode: str = "raw"  # raw | summarized | sliding | slim
    # none | L1_summarize | L2_sliding | L3_template | L4_reject
    downgrade_level: str = "none"
    estimated_tokens: int = 0
    max_round_tokens: int = 0
    rejected: bool = False
    note: str = ""


@dataclass
class ContextGovernor:
    """以「单轮最大 token」为硬顶，超限按阶梯降级。"""

    # 单轮发给 LLM 的最大估算 token（主限额）
    max_round_tokens: int = 32000
    # 相对主限额的降级触发比例
    soft_ratio: float = 0.50  # ≥50% → 对话摘要
    hard_ratio: float = 0.75  # ≥75% → 滑动 K
    template_ratio: float = 0.85  # ≥85% → 模板大纲+描述
    # 兼容旧配置：若显式传入则覆盖比例推导（可选）
    dialog_soft_tokens: int | None = None
    dialog_hard_tokens: int | None = None
    pack_soft_tokens: int | None = None
    pack_hard_tokens: int | None = None
    sliding_k: int = 8
    sliding_k_min: int = 4
    chars_per_token: float = 2.0
    # 证据卡生命周期（按 LLM 轮）
    evidence_fresh_search_turns: int = 2  # 最近 N 轮保留 search 原文
    evidence_read_keep_turns: int = 5  # 最近 N 轮内读过的文件保留正文（与评审滚动窗对齐）

    def _soft(self) -> int:
        if self.dialog_soft_tokens is not None:
            return self.dialog_soft_tokens
        return max(1, int(self.max_round_tokens * self.soft_ratio))

    def _hard(self) -> int:
        if self.dialog_hard_tokens is not None:
            return self.dialog_hard_tokens
        return max(1, int(self.max_round_tokens * self.hard_ratio))

    def _template_at(self) -> int:
        if self.pack_soft_tokens is not None:
            return self.pack_soft_tokens
        return max(1, int(self.max_round_tokens * self.template_ratio))

    def _ceiling(self) -> int:
        if self.pack_hard_tokens is not None:
            return self.pack_hard_tokens
        return self.max_round_tokens

    def estimate_tokens(self, text: str | None) -> int:
        if not text:
            return 0
        return max(1, int(len(text) / self.chars_per_token))

    def estimate_messages(self, messages: list[BaseMessage]) -> int:
        total = 0
        for m in messages:
            total += self.estimate_tokens(_msg_text(m))
            total += 4  # role overhead
        return total

    def govern(
        self,
        messages: list[BaseMessage],
        *,
        is_coding: bool = False,
        allow_reject: bool = True,
    ) -> GovernResult:
        """超过单轮最大 token 限额时降级：摘要→滑动→模板大纲；仍超则拒发（不做极限瘦身）。"""
        _ = is_coding  # 保留参数；滑动 K 由调用方按场景注入 sliding_k
        k = self.sliding_k
        soft, hard = self._soft(), self._hard()
        template_at, ceiling = self._template_at(), self._ceiling()

        system, dialog = _split_buckets(list(messages))
        dialog_mode = "raw"
        template_level = "full"
        downgrade = "none"

        # 精确重复读（同读键）始终折叠：path 相同但行范围不同不算重复
        dialog = _dedupe_identical_reads(dialog)
        # 证据卡：search→索引卡；同文件续读合并；过期 read→阅读证据卡
        dialog = _apply_evidence_lifecycle(
            dialog,
            fresh_search_turns=max(1, int(self.evidence_fresh_search_turns)),
            read_keep_turns=max(1, int(self.evidence_read_keep_turns)),
        )

        pack = self.estimate_messages(system + dialog)
        # 未超软阈：不降级
        if pack < soft:
            return GovernResult(
                messages=system + dialog,
                template_level=template_level,
                dialog_mode=dialog_mode,
                downgrade_level=downgrade,
                estimated_tokens=pack,
                max_round_tokens=ceiling,
            )

        # L1：超软阈 → 对话摘要（按 LLM 轮，非用户会话轮）
        dialog = _summarize_old_turns(dialog, keep_last=k)
        dialog_mode = "summarized"
        downgrade = "L1_summarize"
        pack = self.estimate_messages(system + dialog)

        # L2：仍超硬阈 → 滑动 K
        if pack >= hard:
            dialog = _sliding_keep(dialog, k=k)
            dialog_mode = "sliding"
            downgrade = "L2_sliding"
            pack = self.estimate_messages(system + dialog)

        # L3：仍紧 → System 模板改为大纲+描述（不再做骨架/tool 指针化等极限瘦身）
        if pack >= template_at:
            system = [_downgrade_system(m, level="outline") for m in system]
            template_level = "outline"
            downgrade = "L3_template"
            pack = self.estimate_messages(system + dialog)

        # L4：仍 ≥ 单轮最大限额 → 直接拒发（避免极限瘦身导致输出不稳）
        if pack >= ceiling and allow_reject:
            note = (
                f"CONTEXT_EXCEEDED: 单轮估 token≈{pack} 仍 ≥ 限额 {ceiling}；"
                "已执行摘要/滑动/模板大纲降级仍不足，拒发本轮生成。"
                "请拆分任务或缩小材料范围后重试。"
            )
            fixed = system + [
                HumanMessage(
                    content=(
                        "系统：已超过单轮最大 token 限额，本轮不调用模型生成。"
                        "请缩小任务范围、拆分任务卡，或清理会话后「继续」。"
                    )
                )
            ]
            return GovernResult(
                messages=fixed,
                template_level=template_level,
                dialog_mode=dialog_mode,
                downgrade_level="L4_reject",
                estimated_tokens=pack,
                max_round_tokens=ceiling,
                rejected=True,
                note=note,
            )

        return GovernResult(
            messages=system + dialog,
            template_level=template_level,
            dialog_mode=dialog_mode,
            downgrade_level=downgrade,
            estimated_tokens=pack,
            max_round_tokens=ceiling,
            rejected=False,
            note="" if downgrade == "none" else f"单轮上下文降级={downgrade} tokens≈{pack}/{ceiling}",
        )

    def outline_template(self, template: str | None, *, level: str = "outline") -> str:
        if not template or not template.strip():
            return template or ""
        if level == "full":
            return template
        headings = re.findall(r"(?m)^(#{1,3}\s+.+)$", template)
        # 表格表头 / 「必填」类短说明行
        hints = re.findall(r"(?m)^[|\-].{0,120}$", template)
        hints = [h for h in hints if "示例" not in h and "例如" not in h][:20]
        if level == "skeleton":
            body = "\n".join(headings) if headings else template[:400]
            return (
                "【模板已降级为骨架】请严格按下列标题结构 write_output；样例已省略，需要时用 read_docs 读完整模板。\n"
                + body
            )
        parts = []
        if headings:
            parts.append("## 大纲\n" + "\n".join(headings))
        if hints:
            parts.append("## 字段/表格提示\n" + "\n".join(hints[:15]))
        if not parts:
            # 无 markdown 标题时：保留前 800 字作描述
            parts.append("## 描述\n" + template[:800] + ("…" if len(template) > 800 else ""))
        return (
            "【模板已压缩为大纲+描述，长样例已去掉】撰写时遵循下列结构与约束；"
            "细节不确定时用 read_docs 读取完整模板。\n\n" + "\n\n".join(parts)
        )


def _msg_text(m: BaseMessage) -> str:
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


def _split_buckets(messages: list[BaseMessage]) -> tuple[list[BaseMessage], list[BaseMessage]]:
    system: list[BaseMessage] = []
    dialog: list[BaseMessage] = []
    for m in messages:
        if isinstance(m, SystemMessage):
            system.append(m)
        else:
            dialog.append(m)
    return system, dialog


def _iter_turns(dialog: list[BaseMessage]) -> list[list[BaseMessage]]:
    """按「与 LLM 的一轮」切分（对齐产品 llmRound）。

    一轮 = 一次 AIMessage（模型调用）+ 其后紧跟的 ToolMessage，直到下一次 AIMessage。
    开场 / CONTINUE 的 Human 挂到随后那一轮；若末尾仅有 Human，单独成轮。
    """
    turns: list[list[BaseMessage]] = []
    preamble: list[BaseMessage] = []
    cur: list[BaseMessage] | None = None

    for m in dialog:
        if isinstance(m, AIMessage):
            if cur is not None:
                turns.append(cur)
            cur = [*preamble, m]
            preamble = []
        elif isinstance(m, ToolMessage):
            if cur is None:
                cur = [*preamble, m]
                preamble = []
            else:
                cur.append(m)
        elif isinstance(m, HumanMessage):
            if cur is not None:
                turns.append(cur)
                cur = None
            preamble.append(m)
        else:
            if cur is not None:
                cur.append(m)
            else:
                preamble.append(m)

    if cur is not None:
        turns.append(cur)
    elif preamble:
        turns.append(preamble)
    return turns


def _tool_call_meta_index(dialog: list[BaseMessage]) -> dict[str, dict]:
    """tool_call_id → {args, name}（ToolMessage 常无 name，需从 AIMessage 回填）。"""
    out: dict[str, dict] = {}
    for m in dialog:
        if not isinstance(m, AIMessage):
            continue
        for call in getattr(m, "tool_calls", None) or []:
            if not isinstance(call, dict):
                continue
            cid = str(call.get("id") or "")
            if not cid:
                continue
            args = call.get("args") if isinstance(call.get("args"), dict) else {}
            out[cid] = {"args": args, "name": str(call.get("name") or "")}
    return out


def _tool_call_args_index(dialog: list[BaseMessage]) -> dict[str, dict]:
    """兼容旧调用：仅返回 args。"""
    return {k: dict(v.get("args") or {}) for k, v in _tool_call_meta_index(dialog).items()}


def _parse_json_object(text: str) -> dict:
    if not text or not text.lstrip().startswith(("{", "[")):
        return {}
    try:
        data = json.loads(text)
    except Exception:  # noqa: BLE001
        return {}
    return data if isinstance(data, dict) else {}


def _read_range_token(args: dict, body: dict) -> str:
    """行范围参与读键：缺省视为整文件 all（与分段读区分）。"""
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


def _read_key_for_tool(m: ToolMessage, args: dict | None) -> str | None:
    """读材料/读代码的去重键：path 相同但 start/end 不同 → 不同键，允许分段读。"""
    args = args or {}
    body = _parse_json_object(_msg_text(m))
    name = (getattr(m, "name", None) or args.get("_tool_name") or "").strip()
    rng = _read_range_token(args, body)

    rel = (
        body.get("relativePath")
        or body.get("relative_path")
        or args.get("relative_path")
        or args.get("relativePath")
    )
    display = body.get("displayPath") or args.get("displayPath") or args.get("display_name")
    source = body.get("source") or args.get("source")
    node_id = (
        body.get("nodeId")
        or body.get("node_id")
        or args.get("node_id")
        or args.get("nodeId")
    )

    # 代码仓
    if rel or name in {"read_file", "write_file"}:
        path = str(rel or display or "").replace("\\", "/").strip()
        if not path:
            return None
        return f"code|{path}|{rng}"

    # Wiki / 工作区文档
    if source or node_id is not None or name in {"read_docs", "write_output"}:
        path = str(display or rel or "").replace("\\", "/").strip()
        src = str(source or "").strip() or "-"
        nid = str(node_id if node_id is not None else "-")
        # path 与 node 都空则无法键控
        if path == "" and nid == "-":
            return None
        return f"doc|{src}|{nid}|{path or '-'}|{rng}"

    # 无 name 时仍尝试从 body 推断
    if rel:
        return f"code|{str(rel).replace(chr(92), '/')}|{rng}"
    if display or node_id is not None:
        return (
            f"doc|{str(source or '-')}|{node_id if node_id is not None else '-'}|"
            f"{str(display or '-').replace(chr(92), '/')}|{rng}"
        )
    return None


def _dedupe_identical_reads(dialog: list[BaseMessage]) -> list[BaseMessage]:
    """同一读键只保留最后一次全文；更早的改为指针。

    读键含行范围，故 PRD 1-160 与 160-330 会同时保留；仅完全相同范围的重复读才折叠。
    """
    args_by_id = _tool_call_args_index(dialog)
    # 从后往前：每个 key 第一次遇到的是「最后一次读」
    keep_full: set[int] = set()
    seen: set[str] = set()
    for i in range(len(dialog) - 1, -1, -1):
        m = dialog[i]
        if not isinstance(m, ToolMessage):
            continue
        # 指针/证据卡不参与「保留全文」竞选，避免把真读挤成 dedup
        if _is_card_text(_msg_text(m)):
            continue
        cid = getattr(m, "tool_call_id", None) or ""
        key = _read_key_for_tool(m, args_by_id.get(cid))
        if not key:
            continue
        if key in seen:
            continue
        seen.add(key)
        keep_full.add(i)

    if not seen:
        return dialog

    out: list[BaseMessage] = []
    for i, m in enumerate(dialog):
        if not isinstance(m, ToolMessage):
            out.append(m)
            continue
        if _is_card_text(_msg_text(m)):
            out.append(m)
            continue
        cid = getattr(m, "tool_call_id", None) or ""
        key = _read_key_for_tool(m, args_by_id.get(cid))
        if not key or i in keep_full:
            out.append(m)
            continue
        stub = ToolMessage(
            content=(
                f"[read dedup] key={key} 正文已在后续同范围读取中保留；"
                f"此处省略 {len(_msg_text(m))} 字。"
            ),
            tool_call_id=cid,
        )
        # 尽量保留原 name，便于后续摘要识别工具类型
        try:
            if getattr(m, "name", None):
                stub.name = m.name  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass
        out.append(stub)
    return out


def _is_card_text(text: str) -> bool:
    t = (text or "").lstrip()
    return t.startswith(
        (
            "[搜索证据]",
            "[阅读证据]",
            "[read dedup]",
            "[read merged]",
            "[阅读指针]",
            "[read pointer]",
            "[list_dir 冷却]",
        )
    )


def _clone_tool(m: ToolMessage, content: str) -> ToolMessage:
    stub = ToolMessage(content=content, tool_call_id=getattr(m, "tool_call_id", "") or "")
    try:
        if getattr(m, "name", None):
            stub.name = m.name  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass
    return stub


def _resolve_tool_name(m: ToolMessage, meta: dict | None) -> str:
    name = (getattr(m, "name", None) or "").strip()
    if name:
        return name
    if meta and meta.get("name"):
        return str(meta["name"]).strip()
    # 从正文形态兜底
    body = _parse_json_object(_msg_text(m))
    if "hits" in body and ("repoPath" in body or "engine" in body or "scannedFiles" in body):
        return "search_code" if "repoPath" in body else "search_docs"
    if "relativePath" in body or "content" in body:
        return "read_file" if "relativePath" in body else "read_docs"
    return ""


def _file_identity_for_read(m: ToolMessage, args: dict | None) -> str | None:
    """去掉行范围后的文件身份，用于续读合并 / 过期收卡。"""
    key = _read_key_for_tool(m, args)
    if not key:
        return None
    # code|path|range  /  doc|src|nid|path|range
    parts = key.split("|")
    if len(parts) < 2:
        return key
    return "|".join(parts[:-1])


def _read_span(args: dict, body: dict) -> tuple[int | None, int | None]:
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
    try:
        s = int(start) if start is not None else None
    except Exception:  # noqa: BLE001
        s = None
    try:
        e = int(end) if end is not None else None
    except Exception:  # noqa: BLE001
        e = None
    return s, e


def _build_search_index_card(
    *,
    tool_name: str,
    args: dict,
    body: dict,
    llm_round: int,
) -> str:
    query = str(args.get("query") or body.get("query") or "?").strip() or "?"
    hits = body.get("hits") if isinstance(body.get("hits"), list) else []
    files: dict[str, dict] = {}
    for h in hits:
        if not isinstance(h, dict):
            continue
        path = str(
            h.get("relativePath")
            or h.get("displayPath")
            or h.get("path")
            or "?"
        ).replace("\\", "/")
        node_id = h.get("nodeId") if h.get("nodeId") is not None else h.get("node_id")
        key = f"{path}|{node_id if node_id is not None else ''}"
        slot = files.setdefault(
            key,
            {"path": path, "nodeId": node_id, "lines": [], "snippet": ""},
        )
        line = h.get("line")
        if line is not None:
            try:
                ln = int(line)
                if ln not in slot["lines"] and len(slot["lines"]) < 5:
                    slot["lines"].append(ln)
            except Exception:  # noqa: BLE001
                pass
        if not slot["snippet"]:
            sn = str(h.get("snippet") or h.get("text") or "").strip().replace("\n", " ")
            if sn:
                slot["snippet"] = sn[:120]

    lines = [
        f"[搜索证据] {tool_name} query={query!r}",
        f"命中 {len(files)} 个文件 / {len(hits)} 条 · llmRound≈{llm_round}",
    ]
    # 编码场景可稍宽：最多列 25 个文件
    for i, slot in enumerate(files.values()):
        if i >= 25:
            lines.append(f"…另有 {len(files) - 25} 个文件未列出")
            break
        path = slot["path"]
        nid = slot["nodeId"]
        loc = f"{path}" + (f" nodeId={nid}" if nid is not None else "")
        ls = ",".join(str(x) for x in slot["lines"]) or "-"
        extra = f"  snippet={slot['snippet']}" if slot["snippet"] else ""
        lines.append(f"- {loc}  lines={ls}{extra}")
    lines.append("（原始命中列表已折叠；需细节则按 path/nodeId+行号 read）")
    return "\n".join(lines)


def _build_read_evidence_card(
    *,
    tool_name: str,
    file_id: str,
    args: dict,
    body: dict,
    contents: list[str],
    spans: list[tuple[int | None, int | None]],
    llm_round: int,
) -> str:
    path = (
        body.get("relativePath")
        or body.get("displayPath")
        or args.get("relative_path")
        or args.get("displayPath")
        or file_id
    )
    source = body.get("source") or args.get("source")
    node_id = body.get("nodeId") or body.get("node_id") or args.get("node_id") or args.get("nodeId")
    # 合并区间展示
    nums = [(s, e) for s, e in spans if s is not None or e is not None]
    if nums:
        s_min = min(s if s is not None else 1 for s, _ in nums)
        e_max = max(e if e is not None else s_min for s, e in nums)
        rng = f"{s_min}-{e_max}"
    else:
        rng = "all"

    # 确定性要点：标题行 + 前若干非空行
    blob = "\n".join(contents)
    tip_lines: list[str] = []
    for ln in blob.splitlines():
        t = ln.strip()
        if not t:
            continue
        if t.startswith("#") or t.startswith("|") or t.startswith("- ") or ":" in t[:40]:
            tip_lines.append(t[:160])
        elif len(tip_lines) < 3:
            tip_lines.append(t[:160])
        if len(tip_lines) >= 8:
            break
    if not tip_lines:
        tip_lines = [blob[:200].replace("\n", " ") + ("…" if len(blob) > 200 else "")]

    head = f"[阅读证据] {tool_name} {path}  行 {rng} · llmRound≈{llm_round}"
    if source or node_id is not None:
        head += f"  (source={source or '-'} nodeId={node_id if node_id is not None else '-'})"
    return head + "\n要点：\n- " + "\n- ".join(tip_lines) + "\n（原文已折叠；需复核则按 path+行号再 read）"


def _merge_read_workspace_content(
    parts: list[tuple[int | None, int | None, str]],
) -> tuple[str, int | None, int | None]:
    """按行号排序合并相邻/重叠段，生成工作区正文。"""
    if not parts:
        return "", None, None
    # 无法解析行号时按出现顺序拼接
    if all(s is None and e is None for s, e, _ in parts):
        text = "\n\n-----\n\n".join(c for _, _, c in parts if c)
        return text, None, None
    ordered = sorted(parts, key=lambda x: (x[0] is None, x[0] or 0, x[1] or 0))
    chunks: list[str] = []
    s_min: int | None = None
    e_max: int | None = None
    for s, e, content in ordered:
        label_s = s if s is not None else "?"
        label_e = e if e is not None else "?"
        chunks.append(f"<<<lines {label_s}-{label_e}>>>\n{content}")
        if s is not None:
            s_min = s if s_min is None else min(s_min, s)
        if e is not None:
            e_max = e if e_max is None else max(e_max, e)
        elif s is not None:
            e_max = s if e_max is None else max(e_max, s)
    return "\n\n".join(chunks), s_min, e_max


def _apply_evidence_lifecycle(
    dialog: list[BaseMessage],
    *,
    fresh_search_turns: int = 1,
    read_keep_turns: int = 2,
) -> list[BaseMessage]:
    """P0 证据卡生命周期。

    - search：不在最近 fresh_search_turns 个 LLM 轮内的 → 索引卡（防重搜）
    - read：同文件在最近 read_keep_turns 轮内 → 合并工作区保留正文；更早 → 阅读证据卡
    """
    turns = _iter_turns(dialog)
    if not turns:
        return dialog

    n = len(turns)
    meta_by_id = _tool_call_meta_index(dialog)

    # 消息 → 所属 LLM 轮下标
    turn_of: dict[int, int] = {}
    msg_index = {id(m): i for i, m in enumerate(dialog)}
    for ti, turn in enumerate(turns):
        for m in turn:
            if id(m) in msg_index:
                turn_of[msg_index[id(m)]] = ti

    search_cutoff = max(0, n - fresh_search_turns)
    read_cutoff = max(0, n - read_keep_turns)

    # 收集 read：file_id → [(msg_idx, turn_idx, args, body, span, content)]
    reads: dict[str, list[tuple[int, int, dict, dict, tuple[int | None, int | None], str]]] = {}
    for i, m in enumerate(dialog):
        if not isinstance(m, ToolMessage):
            continue
        text = _msg_text(m)
        if _is_card_text(text):
            continue
        cid = getattr(m, "tool_call_id", None) or ""
        meta = meta_by_id.get(cid) or {}
        args = dict(meta.get("args") or {})
        name = _resolve_tool_name(m, meta)
        if name not in {"read_docs", "read_file"} and not (
            _parse_json_object(text).get("content") and (
                _parse_json_object(text).get("relativePath")
                or args.get("node_id")
                or args.get("relative_path")
            )
        ):
            # 非 read；search 另处理
            continue
        if name not in {"read_docs", "read_file"}:
            # 兜底当成 read
            name = "read_file" if args.get("relative_path") or _parse_json_object(text).get("relativePath") else "read_docs"
        body = _parse_json_object(text)
        if body.get("error"):
            continue
        fid = _file_identity_for_read(m, args)
        if not fid:
            continue
        content = str(body.get("content") or "")
        if not content and not _is_card_text(text):
            # 非 JSON 正文也尽量保留
            content = text
        span = _read_span(args, body)
        ti = turn_of.get(i, 0)
        reads.setdefault(fid, []).append((i, ti, args, body, span, content))

    # 计划替换
    replace: dict[int, str] = {}

    # 1) search → 索引卡
    for i, m in enumerate(dialog):
        if not isinstance(m, ToolMessage):
            continue
        text = _msg_text(m)
        if _is_card_text(text):
            continue
        cid = getattr(m, "tool_call_id", None) or ""
        meta = meta_by_id.get(cid) or {}
        args = dict(meta.get("args") or {})
        name = _resolve_tool_name(m, meta)
        body = _parse_json_object(text)
        is_search = name in {"search_docs", "search_code"} or (
            isinstance(body.get("hits"), list) and ("engine" in body or "repoPath" in body or "scannedFiles" in body)
        )
        if not is_search:
            continue
        ti = turn_of.get(i, 0)
        if ti >= search_cutoff:
            continue  # 最近轮保留原文供本轮决策
        if not isinstance(body.get("hits"), list):
            # 无法解析则轻量折叠
            replace[i] = (
                f"[搜索证据] {name or 'search'} query={args.get('query')!r}\n"
                f"（结果已折叠 {len(text)} 字；llmRound≈{ti + 1}）"
            )
            continue
        tool = name or ("search_code" if "repoPath" in body else "search_docs")
        replace[i] = _build_search_index_card(
            tool_name=tool, args=args, body=body, llm_round=ti + 1
        )

    # 2) read：活跃文件合并工作区；过期收阅读证据卡
    for fid, items in reads.items():
        items_sorted = sorted(items, key=lambda x: (x[1], x[0]))
        last_ti = items_sorted[-1][1]
        if last_ti >= read_cutoff:
            # 文件仍在续读窗口：该文件所有分段合并进最新一条（避免 1-10 卡片化后只读 10-20 丢上文）
            merged_text, s_min, e_max = _merge_read_workspace_content(
                [(span[0], span[1], content) for _i, _t, _a, _b, span, content in items_sorted]
            )
            last_idx = items_sorted[-1][0]
            last_body = dict(items_sorted[-1][3])
            last_body["content"] = merged_text
            if s_min is not None:
                last_body["startLine"] = s_min
            if e_max is not None:
                last_body["endLine"] = e_max
            replace[last_idx] = json.dumps(last_body, ensure_ascii=False)
            for it in items_sorted[:-1]:
                idx, _ti, _args, _body, span, content = it
                replace[idx] = (
                    f"[read merged] file={fid} 分段已并入同文件工作区"
                    f"（原 {span[0] or '?'}-{span[1] or '?'}，{len(content)} 字）"
                )
        else:
            # 过期：最新一条变阅读证据卡，其余 stub
            last_idx, last_ti, last_args, last_body, _last_span, _last_content = items_sorted[-1]
            all_contents = [it[5] for it in items_sorted]
            all_spans = [it[4] for it in items_sorted]
            name_guess = "read_file" if fid.startswith("code|") else "read_docs"
            # 优先用最后一条 meta
            cid = getattr(dialog[last_idx], "tool_call_id", None) or ""
            meta = meta_by_id.get(cid) or {}
            name = _resolve_tool_name(dialog[last_idx], meta) or name_guess  # type: ignore[arg-type]
            replace[last_idx] = _build_read_evidence_card(
                tool_name=name,
                file_id=fid,
                args=last_args,
                body=last_body,
                contents=all_contents,
                spans=all_spans,
                llm_round=last_ti + 1,
            )
            for it in items_sorted[:-1]:
                idx, _ti, _args, _body, span, _content = it
                replace[idx] = (
                    f"[read merged] file={fid} 已收入阅读证据卡"
                    f"（原 {span[0] or '?'}-{span[1] or '?'}）"
                )

    if not replace:
        return dialog

    out: list[BaseMessage] = []
    for i, m in enumerate(dialog):
        if i in replace and isinstance(m, ToolMessage):
            out.append(_clone_tool(m, replace[i]))
        else:
            out.append(m)
    return out


def _summarize_turn(turn: list[BaseMessage], idx: int) -> HumanMessage:
    bits: list[str] = [f"[摘要] LLM第{idx}轮："]
    for m in turn:
        if isinstance(m, HumanMessage):
            t = _msg_text(m).strip().replace("\n", " ")
            bits.append(f"用户「{t[:120]}{'…' if len(t) > 120 else ''}」")
        elif isinstance(m, AIMessage):
            t = _msg_text(m).strip().replace("\n", " ")
            tools = getattr(m, "tool_calls", None) or []
            if tools:
                names = ",".join(str(c.get("name") or "") for c in tools)
                bits.append(f"模型拟调用工具[{names}]")
            elif t:
                bits.append(f"模型「{t[:100]}{'…' if len(t) > 100 else ''}」")
        elif isinstance(m, ToolMessage):
            name = getattr(m, "name", None) or "tool"
            t = _msg_text(m)
            path = ""
            m_path = re.search(r'"displayPath"\s*:\s*"([^"]+)"', t) or re.search(
                r'"relativePath"\s*:\s*"([^"]+)"', t
            )
            if m_path:
                path = m_path.group(1)
            # 摘要里带上范围，避免分段读被误合成同一指针
            rng = ""
            m_s = re.search(r'"startLine"\s*:\s*(\d+)', t)
            m_e = re.search(r'"endLine"\s*:\s*(\d+)', t)
            if m_s or m_e:
                rng = f":{m_s.group(1) if m_s else '?'}−{m_e.group(1) if m_e else '?'}"
            err = '"error"' in t[:200]
            bits.append(
                f"{name}{'→' + path if path else ''}{rng}"
                f"{'失败' if err else '完成'}({min(len(t), 9999)}字)"
            )
    return HumanMessage(content=" ".join(bits))


def _summarize_old_turns(dialog: list[BaseMessage], *, keep_last: int) -> list[BaseMessage]:
    turns = _iter_turns(dialog)
    if len(turns) <= keep_last:
        # LLM 轮数不多时仍可能单轮 Tool 过大：截断超长 Tool
        return [_cap_tool_in_place(m) for m in dialog]
    head = turns[:-keep_last]
    tail = turns[-keep_last:]
    summaries = [_summarize_turn(t, i + 1) for i, t in enumerate(head)]
    # 多段摘要合并为一条，减少噪声
    if len(summaries) > 1:
        merged = HumanMessage(content="\n".join(_msg_text(s) for s in summaries))
        out: list[BaseMessage] = [merged]
    else:
        out = list(summaries)
    for t in tail:
        out.extend(_cap_tool_in_place(m) for m in t)
    return out


def _sliding_keep(dialog: list[BaseMessage], *, k: int) -> list[BaseMessage]:
    turns = _iter_turns(dialog)
    if len(turns) <= k:
        return [_cap_tool_in_place(m) for m in dialog]
    kept = turns[-k:]
    # 窗口外留一条总摘要
    old = turns[:-k]
    summary = _summarize_turn(
        [m for t in old for m in t],
        0,
    )
    summary.content = "[摘要] 更早 LLM 轮次已滑动丢弃原文，要点：" + _msg_text(summary).replace(
        "[摘要] LLM第0轮：", ""
    )
    out: list[BaseMessage] = [summary]
    for t in kept:
        out.extend(_cap_tool_in_place(m) for m in t)
    return out


def _cap_tool_in_place(m: BaseMessage, limit: int = 6000) -> BaseMessage:
    if not isinstance(m, ToolMessage):
        return m
    text = _msg_text(m)
    if len(text) <= limit:
        return m
    return ToolMessage(
        content=text[:limit] + f"…(truncated {len(text) - limit} chars)",
        tool_call_id=getattr(m, "tool_call_id", "") or "",
    )


def _slim_tool_bodies(m: BaseMessage) -> BaseMessage:
    if not isinstance(m, ToolMessage):
        return m
    text = _msg_text(m)
    path = ""
    m_path = re.search(r'"displayPath"\s*:\s*"([^"]+)"', text) or re.search(
        r'"relativePath"\s*:\s*"([^"]+)"', text
    )
    if m_path:
        path = m_path.group(1)
    aid = ""
    m_aid = re.search(r'"actionId"\s*:\s*"([^"]+)"', text)
    if m_aid:
        aid = m_aid.group(1)
    err = "error" in text[:300].lower()
    stub = f"[tool slim] path={path or '-'} actionId={aid or '-'} {'FAIL' if err else 'OK'} chars={len(text)}"
    return ToolMessage(content=stub, tool_call_id=getattr(m, "tool_call_id", "") or "")


def _downgrade_system(m: SystemMessage, *, level: str) -> SystemMessage:
    text = _msg_text(m)
    gov = ContextGovernor()
    # 拆出「交付物模板」段落压缩，其余短说明保留
    marker = "输出参考模板"
    alt = "交付物模板提示"
    if marker in text or alt in text:
        # 粗切：从模板提示起到下一 ## 或「材料索引」
        pattern = re.compile(
            r"(输出参考模板[^\n]*\n|交付物模板提示[^\n]*\n)([\s\S]*?)(?=\n材料索引：|\n## |\Z)"
        )

        def repl(mo: re.Match[str]) -> str:
            head, body = mo.group(1), mo.group(2)
            return head + gov.outline_template(body, level=level) + "\n"

        text2 = pattern.sub(repl, text, count=1)
        if text2 == text and len(text) > 4000:
            text2 = gov.outline_template(text, level=level)
        text = text2
    elif len(text) > 6000:
        text = gov.outline_template(text, level=level)

    if "必须遵守的规范" in text and level in {"outline", "skeleton"}:
        text = re.sub(
            r"## 必须遵守的规范[\s\S]*?(?=\n材料索引：|\n## |\Z)",
            "## 必须遵守的规范\n（正文已降级）请用 read_docs 读取 role=standards 材料，勿违反规范约束。\n",
            text,
            count=1,
        )
    return SystemMessage(content=text)
