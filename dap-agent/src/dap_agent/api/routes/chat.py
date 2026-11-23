from __future__ import annotations

import asyncio
import json
import logging

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from dap_agent.agents.title_agent import summarize_title
from dap_agent.agents.workbench_agent import stream_reply
from dap_agent.api.deps import verify_internal_token
from dap_agent.runtime.session_registry import session_registry
from dap_agent.schemas.chat import ChatStreamRequest, StopRequest, StopResponse, TitleRequest, TitleResponse

logger = logging.getLogger("dap_agent.chat")
router = APIRouter(prefix="/v1/chat", dependencies=[Depends(verify_internal_token)])


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/stream")
async def chat_stream(req: ChatStreamRequest):
    logger.info(
        "stream start run_id=%s session_id=%s position=%s skills=%s messages=%s",
        req.run_id,
        req.session_id,
        req.position.name if req.position else None,
        len(req.skills or []),
        len(req.messages or []),
    )
    queue: asyncio.Queue[str | None] = asyncio.Queue()

    async def producer() -> None:
        try:
            async for ev in stream_reply(req):
                etype = ev.get("type") if isinstance(ev, dict) else None
                if etype == "step":
                    payload = {
                        "step": ev.get("step") or "step",
                        "message": ev.get("message") or "",
                    }
                    if ev.get("llmRound") is not None:
                        payload["llmRound"] = ev.get("llmRound")
                    if ev.get("detail") is not None:
                        payload["detail"] = ev.get("detail")
                    await queue.put(_sse("step", payload))
                elif etype == "content":
                    delta = ev.get("delta") or ""
                    if delta:
                        await queue.put(_sse("content", {"delta": delta}))
                elif etype == "usage":
                    await queue.put(
                        _sse(
                            "usage",
                            {
                                "prompt_tokens": int(ev.get("prompt_tokens") or 0),
                                "completion_tokens": int(ev.get("completion_tokens") or 0),
                                "total_tokens": int(ev.get("total_tokens") or 0),
                            },
                        )
                    )
                elif isinstance(ev, str) and ev:
                    # 兼容旧版纯文本 yield
                    await queue.put(_sse("content", {"delta": ev}))
            await queue.put(_sse("done", {"run_id": req.run_id}))
            logger.info("stream done run_id=%s", req.run_id)
        except asyncio.CancelledError:
            logger.info("stream cancelled run_id=%s", req.run_id)
            await queue.put(_sse("done", {"run_id": req.run_id, "cancelled": True}))
            raise
        except Exception as exc:  # noqa: BLE001
            logger.exception("stream error run_id=%s: %s", req.run_id, exc)
            await queue.put(_sse("error", {"message": str(exc)}))
            await queue.put(_sse("done", {"run_id": req.run_id}))
        finally:
            await queue.put(None)
            session_registry.remove(req.run_id)

    task = asyncio.create_task(producer())
    session_registry.register(req.run_id, task)

    async def event_generator():
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                yield item
        finally:
            if not task.done():
                task.cancel()

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@router.post("/title", response_model=TitleResponse)
async def chat_title(req: TitleRequest) -> TitleResponse:
    logger.info("title start question_len=%s", len(req.question or ""))
    try:
        title = await summarize_title(req.question)
    except Exception:  # noqa: BLE001
        logger.exception("title failed, fallback truncate")
        title = (req.question or "新对话").strip()[:30] or "新对话"
    logger.info("title done title=%s", title)
    return TitleResponse(title=title)


@router.post("/stop", response_model=StopResponse)
async def chat_stop(req: StopRequest) -> StopResponse:
    cancelled = session_registry.cancel(req.run_id)
    logger.info("stop run_id=%s cancelled=%s", req.run_id, cancelled)
    return StopResponse(ok=True, cancelled=cancelled)
