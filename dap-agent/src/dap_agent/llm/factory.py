from __future__ import annotations

import logging

from langchain_openai import ChatOpenAI

from dap_agent.config import settings

logger = logging.getLogger("dap_agent.llm")


def normalize_base_url(url: str) -> str:
    """OpenAI 兼容客户端会在 base_url 后拼 /chat/completions，通常需要以 /v1 结尾。"""
    u = (url or "").strip().rstrip("/")
    if not u:
        return u
    if u.endswith("/v1"):
        return u
    # 常见误配：DashScope compatible-mode 漏写 /v1
    if u.endswith("/compatible-mode"):
        fixed = u + "/v1"
        logger.warning("LLM_BASE_URL missing /v1, auto-fixed: %s -> %s", url, fixed)
        return fixed
    logger.warning(
        "LLM_BASE_URL=%s does not end with /v1; if chat returns 404, append /v1",
        u,
    )
    return u


def create_chat_model(*, temperature: float = 0.3) -> ChatOpenAI:
    base_url = normalize_base_url(settings.llm_base_url)
    # langchain 默认 stream_chunk_timeout=120；编码任务思考停顿易误杀
    raw_timeout = float(settings.llm_stream_chunk_timeout_s)
    stream_chunk_timeout: float | None = None if raw_timeout <= 0 else raw_timeout
    logger.info(
        "create ChatOpenAI model=%s base_url=%s stream_chunk_timeout=%s",
        settings.llm_model,
        base_url,
        stream_chunk_timeout,
    )
    return ChatOpenAI(
        base_url=base_url,
        api_key=settings.llm_api_key or "EMPTY",
        model=settings.llm_model,
        temperature=temperature,
        streaming=True,
        stream_chunk_timeout=stream_chunk_timeout,
    )
