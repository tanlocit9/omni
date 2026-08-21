from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Protocol

logger = logging.getLogger("omni.query.audit")


@dataclass(frozen=True)
class QueryAuditEvent:
    query_id: str
    actor: str
    sql_hash: str
    state: str
    created_at: datetime
    completed_at: datetime | None
    duration_ms: int | None
    row_count: int | None
    data_versions: dict[str, str]


class AuditSink(Protocol):
    def write(self, event: QueryAuditEvent) -> None: ...


class StructuredLogAuditSink:
    def write(self, event: QueryAuditEvent) -> None:
        payload = asdict(event)
        payload["created_at"] = event.created_at.isoformat()
        payload["completed_at"] = (
            event.completed_at.isoformat() if event.completed_at else None
        )
        logger.info("query_audit=%s", json.dumps(payload, sort_keys=True))
