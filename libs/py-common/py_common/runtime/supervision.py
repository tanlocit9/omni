"""Sequential worker recovery without advancing failed Kafka offsets."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

logger = logging.getLogger(__name__)


class WorkerSupervisor:
    """Restart a fully cleaned-up session after failures with capped backoff."""

    def __init__(
        self,
        session: Callable[[], Awaitable[None]],
        *,
        name: str,
        retry_delay: float = 5,
        max_delay: float = 60,
    ) -> None:
        self._session = session
        self._name = name
        self._retry_delay = retry_delay
        self._max_delay = max_delay
        self._active = False
        self._task: asyncio.Task[None] | None = None

    @property
    def is_running(self) -> bool:
        return self._active and self._task is not None and not self._task.done()

    async def run(self) -> None:
        self._task = asyncio.current_task()
        delay = self._retry_delay
        try:
            while True:
                try:
                    self._active = True
                    await self._session()
                    # A worker returning unexpectedly must not remain healthy.
                    raise RuntimeError("Worker session exited")
                except asyncio.CancelledError:
                    raise
                except Exception:
                    self._active = False
                    if self._task.cancelling():
                        raise
                    logger.exception(
                        "Worker %s stopped; retrying in %ss without offset reset",
                        self._name,
                        delay,
                    )
                    await asyncio.sleep(delay)
                    delay = min(delay * 2, self._max_delay)
        finally:
            self._active = False
