from __future__ import annotations

import json
from typing import Any


def decode_json_object_payload(
    payload: str | bytes | dict[str, Any],
    label: str = "Kafka job",
) -> dict[str, Any]:
    """Decode a Kafka payload and require a JSON object contract."""
    if isinstance(payload, dict):
        return payload
    if isinstance(payload, bytes):
        payload = payload.decode("utf-8")
    decoded = json.loads(payload)
    if not isinstance(decoded, dict):
        raise ValueError(f"{label} payload must be a JSON object")
    return decoded
