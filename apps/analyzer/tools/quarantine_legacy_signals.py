"""One-time quarantine for signal Parquet without authoritative lineage.

Run without ``--apply`` to inspect candidates. Applied runs copy each legacy object
to a timestamped quarantine prefix, verify the copy, and only then delete the source.
Regenerate signal data before running full metadata synchronization again.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import re
from dataclasses import dataclass
from datetime import UTC, datetime

import pyarrow.parquet as pq
from minio.commonconfig import CopySource
from py_common.storage.adapters.factory import create_minio_client

from app.settings import AppSettings

_LINEAGE_COLUMNS = {
    "symbol_key",
    "eod_data_version",
    "indicators_data_version",
}
_DATA_VERSION = re.compile(r"sha256:[0-9a-f]{64}")


@dataclass(frozen=True)
class Inspection:
    object_name: str
    reason: str | None

    @property
    def legacy(self) -> bool:
        return self.reason is not None


def inspect_signal_parquet(object_name: str, payload: bytes) -> Inspection:
    """Return a bounded reason when signal bytes cannot prove exact lineage."""
    try:
        frame = pq.read_table(io.BytesIO(payload)).to_pandas()
    except Exception:
        return Inspection(object_name, "invalid Parquet")

    missing = sorted(_LINEAGE_COLUMNS.difference(frame.columns))
    if missing:
        return Inspection(object_name, f"missing columns: {', '.join(missing)}")
    if frame.empty:
        return Inspection(object_name, "empty signal dataset")

    for column in ("eod_data_version", "indicators_data_version"):
        valid = frame[column].map(
            lambda value: (
                isinstance(value, str) and bool(_DATA_VERSION.fullmatch(value))
            )
        )
        if not valid.all():
            return Inspection(object_name, f"invalid values in {column}")

    symbol_keys = frame["symbol_key"].map(
        lambda value: (
            isinstance(value, str)
            and len(value.split("-", maxsplit=1)) == 2
            and all(part.strip() for part in value.split("-", maxsplit=1))
        )
    )
    if not symbol_keys.all():
        return Inspection(object_name, "invalid values in symbol_key")
    return Inspection(object_name, None)


def _read_object(client, bucket: str, object_name: str) -> bytes:
    response = client.get_object(bucket, object_name)
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def _quarantine_object(
    client, bucket: str, source: str, target: str, source_payload: bytes
) -> None:
    client.copy_object(bucket, target, CopySource(bucket, source))
    copied = _read_object(client, bucket, target)
    if hashlib.sha256(copied).digest() != hashlib.sha256(source_payload).digest():
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
        default="_quarantine/plan-014-legacy-signals",
        help="Trusted internal prefix for retained legacy objects",
    )
    args = parser.parse_args()

    settings = AppSettings()
    client = create_minio_client(settings.minio)
    bucket = settings.minio.bucket
    run_id = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    inspected = legacy = quarantined = 0

    for item in client.list_objects(bucket, prefix="signals/", recursive=True):
        if not item.object_name.endswith(".parquet"):
            continue
        inspected += 1
        payload = _read_object(client, bucket, item.object_name)
        result = inspect_signal_parquet(item.object_name, payload)
        if not result.legacy:
            continue
        legacy += 1
        print(f"LEGACY {result.object_name}: {result.reason}")
        if args.apply:
            target = f"{args.quarantine_prefix.rstrip('/')}/{run_id}/{item.object_name}"
            _quarantine_object(client, bucket, item.object_name, target, payload)
            quarantined += 1
            print(f"QUARANTINED {item.object_name} -> {target}")

    action = "quarantined" if args.apply else "would quarantine"
    action_count = quarantined if args.apply else legacy
    print(f"Inspected {inspected}; legacy {legacy}; {action} {action_count}")
    if legacy and not args.apply:
        print(
            "Dry run only. Re-run with --apply, regenerate signals, "
            "then run SYNC_METADATA."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
