"""Read-only inspection of authoritative lineage in canonical indicator Parquet."""

from __future__ import annotations

import io
import re

import pyarrow.parquet as pq

from app.settings import AppSettings
from py_common.storage.adapters.factory import create_minio_client

_DATA_VERSION = re.compile(r"sha256:[0-9a-f]{64}")


def _read_object(client, bucket: str, object_name: str) -> bytes:
    response = client.get_object(bucket, object_name)
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def main() -> int:
    settings = AppSettings()
    client = create_minio_client(settings.minio)
    failures: list[tuple[str, str]] = []
    inspected = 0

    for item in client.list_objects(
        settings.minio.bucket, prefix="indicators/", recursive=True
    ):
        object_name = item.object_name
        if not object_name.endswith(".parquet"):
            continue
        inspected += 1
        payload = _read_object(client, settings.minio.bucket, object_name)
        try:
            table = pq.read_table(
                io.BytesIO(payload), columns=["eod_data_version"]
            )
        except Exception as exc:
            failures.append((object_name, f"missing/unreadable column: {exc}"))
            continue
        versions = table.column("eod_data_version").to_pylist()
        if not versions or any(
            not isinstance(value, str) or not _DATA_VERSION.fullmatch(value)
            for value in versions
        ):
            failures.append((object_name, "null or invalid eod_data_version values"))

    print(f"Inspected {inspected}; invalid indicator lineage {len(failures)}")
    for object_name, reason in failures:
        print(f"INVALID {object_name}: {reason}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
