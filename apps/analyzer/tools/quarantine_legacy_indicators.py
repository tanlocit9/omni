"""One-time quarantine for indicator Parquet without authoritative lineage.

Run without ``--apply`` to inspect candidates. Applied runs copy each legacy object
to a stable quarantine prefix, verify the copy, and only then delete the source.
The operation is resumable: an existing verified copy is reused on a later run.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import re

import pyarrow.parquet as pq
from minio.commonconfig import CopySource
from py_common.storage.adapters.factory import create_minio_client

from app.settings import AppSettings

_DATA_VERSION = re.compile(r"sha256:[0-9a-f]{64}")
_DEFAULT_QUARANTINE_PREFIX = "_quarantine/plan-014-legacy-indicators"


def _read_object(client, bucket: str, object_name: str) -> bytes:
    response = client.get_object(bucket, object_name)
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def _lineage_failure(payload: bytes) -> str | None:
    try:
        table = pq.read_table(io.BytesIO(payload), columns=["eod_data_version"])
    except Exception:
        return "missing or unreadable eod_data_version column"
    versions = table.column("eod_data_version").to_pylist()
    if not versions or any(
        not isinstance(value, str) or not _DATA_VERSION.fullmatch(value)
        for value in versions
    ):
        return "null or invalid eod_data_version values"
    return None


def _verified_copy_exists(
    client, bucket: str, target: str, source_digest: bytes
) -> bool:
    try:
        copied = _read_object(client, bucket, target)
    except Exception:
        return False
    return hashlib.sha256(copied).digest() == source_digest


def _quarantine_object(
    client, bucket: str, source: str, target: str, source_payload: bytes
) -> None:
    source_digest = hashlib.sha256(source_payload).digest()
    if not _verified_copy_exists(client, bucket, target, source_digest):
        client.copy_object(bucket, target, CopySource(bucket, source))
        if not _verified_copy_exists(client, bucket, target, source_digest):
            raise RuntimeError(f"Quarantine verification failed for {source}")
    client.remove_object(bucket, source)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Copy verified legacy objects to quarantine and delete their sources",
    )
    parser.add_argument(
        "--quarantine-prefix",
        default=_DEFAULT_QUARANTINE_PREFIX,
        help="Stable internal prefix for retained legacy indicator objects",
    )
    args = parser.parse_args()

    settings = AppSettings()
    client = create_minio_client(settings.minio)
    bucket = settings.minio.bucket
    candidates: list[tuple[str, bytes, str]] = []

    for item in client.list_objects(bucket, prefix="indicators/", recursive=True):
        object_name = item.object_name
        if not object_name.endswith(".parquet"):
            continue
        payload = _read_object(client, bucket, object_name)
        reason = _lineage_failure(payload)
        if reason is not None:
            candidates.append((object_name, payload, reason))

    print(f"Legacy indicator candidates: {len(candidates)}")
    if not args.apply:
        for object_name, _, reason in candidates[:50]:
            print(f"LEGACY {object_name}: {reason}")
        if len(candidates) > 50:
            print(f"... and {len(candidates) - 50} more")
        return 1 if candidates else 0

    for index, (object_name, payload, _) in enumerate(candidates, start=1):
        target = f"{args.quarantine_prefix.rstrip('/')}/{object_name}"
        _quarantine_object(client, bucket, object_name, target, payload)
        if index % 50 == 0 or index == len(candidates):
            print(f"Quarantined {index}/{len(candidates)}")

    print(f"Quarantined {len(candidates)} legacy indicator objects")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
