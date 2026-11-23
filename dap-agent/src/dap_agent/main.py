import logging

from fastapi import FastAPI

from dap_agent.api.logging_middleware import RequestLoggingMiddleware
from dap_agent.api.routes import chat, health
from dap_agent.config import settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)

app = FastAPI(title="dap-agent", version="0.1.0")
app.add_middleware(RequestLoggingMiddleware)
app.include_router(health.router)
app.include_router(chat.router)


def main() -> None:
    import uvicorn

    logging.getLogger("dap_agent").info(
        "starting dap-agent host=%s port=%s llm_base_url=%s model=%s",
        settings.host,
        settings.port,
        settings.llm_base_url,
        settings.llm_model,
    )
    uvicorn.run(
        "dap_agent.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
