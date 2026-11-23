from fastapi import Header, HTTPException

from dap_agent.config import settings


async def verify_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    if not x_internal_token or x_internal_token != settings.dap_internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")
