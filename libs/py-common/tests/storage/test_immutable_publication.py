from __future__ import annotations

import pytest

from py_common.storage.immutable_publication import ImmutableDatasetPublisher


class MemoryStorage:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], bytes] = {}
        self.fail_on: str | None = None

    async def write_bytes(
        self, bucket: str, object_name: str, data: bytes, content_type: str
    ) -> None:
        if object_name == self.fail_on:
            raise RuntimeError("injected write failure")
        self.objects[(bucket, object_name)] = data

    async def read_bytes(self, bucket: str, object_name: str) -> bytes:
        return self.objects[(bucket, object_name)]


@pytest.mark.anyio
async def test_publishes_immutable_version_and_ready_last() -> None:
    storage = MemoryStorage()
    publisher = ImmutableDatasetPublisher(storage, storage, "stock-data")

    result = await publisher.publish(
        partition_prefix="intraday/trades/provider=vci/exchange=hose/trading_date=2026-09-09",
        object_name="hpg.parquet",
        data=b"parquet",
        manifest={"dataset": "intraday-trades", "rowCount": 2},
    )

    assert result.data_object.endswith("/hpg.parquet")
    assert ("stock-data", result.version_manifest_object) in storage.objects
    assert storage.objects[("stock-data", result.ready_object)].startswith(
        b'{"dataVersion"'
    )


@pytest.mark.anyio
async def test_failure_before_ready_preserves_old_pointer() -> None:
    storage = MemoryStorage()
    prefix = "intraday/trades/provider=vci/exchange=hose/trading_date=2026-09-09"
    ready = f"{prefix}/READY.json"
    storage.objects[("stock-data", ready)] = b"old-ready"
    publisher = ImmutableDatasetPublisher(storage, storage, "stock-data")
    storage.fail_on = f"{prefix}/_versions/" + "not-known"

    async def reject(_: bytes) -> None:
        raise ValueError("candidate rejected")

    with pytest.raises(ValueError, match="candidate rejected"):
        await publisher.publish(
            partition_prefix=prefix,
            object_name="hpg.parquet",
            data=b"candidate",
            manifest={"dataset": "intraday-trades"},
            validate_data=reject,
        )

    assert storage.objects[("stock-data", ready)] == b"old-ready"
