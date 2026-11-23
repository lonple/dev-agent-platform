from __future__ import annotations

import asyncio
from typing import Dict, Optional


class SessionRegistry:
    def __init__(self) -> None:
        self._tasks: Dict[str, asyncio.Task] = {}

    def register(self, run_id: str, task: asyncio.Task) -> None:
        self._tasks[run_id] = task

    def get(self, run_id: str) -> Optional[asyncio.Task]:
        return self._tasks.get(run_id)

    def remove(self, run_id: str) -> None:
        self._tasks.pop(run_id, None)

    def cancel(self, run_id: str) -> bool:
        task = self._tasks.get(run_id)
        if task is None:
            return False
        task.cancel()
        return True


session_registry = SessionRegistry()
