from __future__ import annotations

import logging
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

logger = logging.getLogger("dap_agent.http")


class RequestLoggingMiddleware(BaseHTTPMiddleware):
    """只打 method/path/status/耗时，不读 body，避免消费请求体导致下游解析失败。"""

    async def dispatch(self, request: Request, call_next) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:8]
        start = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            cost_ms = int((time.perf_counter() - start) * 1000)
            logger.exception(
                "HTTP %s %s failed cost=%sms requestId=%s",
                request.method,
                request.url.path,
                cost_ms,
                request_id,
            )
            raise

        cost_ms = int((time.perf_counter() - start) * 1000)
        response.headers["X-Request-Id"] = request_id
        logger.info(
            "HTTP %s %s status=%s cost=%sms requestId=%s",
            request.method,
            request.url.path,
            response.status_code,
            cost_ms,
            request_id,
        )
        return response
