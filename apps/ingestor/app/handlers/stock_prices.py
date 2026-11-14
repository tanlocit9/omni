import logging
from datetime import date, datetime
from zoneinfo import ZoneInfo

import pandas as pd
from py_common.kafka import decode_json_object_payload
from py_common.messaging import JobStatus, JobStatusMessage, JobStatusPublisher, utc_now
from py_common.messaging.output_publication import (
    capture_output_publication,
    publish_terminal_status,
)
from py_common.storage.atomic_work import cutoff_frame, day_rows
from py_common.storage.date_contracts import normalize_date_contracts
from py_common.storage.output_snapshots import OutputSnapshotIdentity
from py_common.storage.parquet import ParquetStorage

from app.messaging.messages import SymbolJobMessage
from app.messaging.status import build_status
from app.settings import settings
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client
from app.stocks.normalization import normalize_record_list_keys

logger = logging.getLogger(__name__)

_ALL_RECORDS_LIMIT = 10_000


def compute_limit(
    from_date: date | None,
    to_date: date,
) -> int:
    if from_date is None:
        return _ALL_RECORDS_LIMIT
    return max((to_date - from_date).days + 1, 1)


async def fetch_stock_data(
    client: StockClient,
    symbol: str,
    limit: int,
) -> pd.DataFrame:
    records = await client.fetch_recent_stock(symbol=symbol, size=limit)
    if not records:
        return pd.DataFrame()
    return pd.DataFrame(records)


def normalize_stock_price_dataframe_columns(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df.copy()

    normalized_df = pd.DataFrame(normalize_record_list_keys(df.to_dict("records")))

    if "date" not in normalized_df.columns:
        raise ValueError("Missing required stock price field: 'date'")

    return normalized_df


async def process_stock_price_message(
    raw_msg: str | bytes | dict[str, object],
    status_publisher: JobStatusPublisher,
    default_client: StockClient,
    parquet_storage: ParquetStorage,
) -> JobStatusMessage:
    started_at = utc_now()
    payload: dict[str, object] = {}
    publication = None

    try:
        payload = decode_json_object_payload(raw_msg, "Stock price sync job")
        message = SymbolJobMessage.model_validate(payload)
        payload = message.status_payload
        exchange, code = message.parse_symbol_key()

        client = (
            get_or_create_client(message.source) if message.source else default_client
        )

        from_date = message.from_offset.date() if message.from_offset else None
        to_date = message.to_offset.date() if message.to_offset else date.today()
        object_name = settings.get_eod_path(exchange, code)
        if (message.metadata.get("outputCompletion") or {}).get(
            "storagePolicy"
        ) == "VERSIONED":
            existing_df = await parquet_storage.read_work_base_dataframe(
                payload,
                OutputSnapshotIdentity(
                    "eod", {"exchange": exchange.lower(), "code": code.lower()}
                ),
            )
        else:
            existing_df = await parquet_storage.read_optional_dataframe(object_name)
        bootstrap = existing_df is None or existing_df.empty

        if message.business_date is not None:
            from_date = to_date = message.business_date
            today = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
            age = (today - to_date).days
            if age < 0 or age + 30 > _ALL_RECORDS_LIMIT:
                raise ValueError("Provider cannot serve requested atomic date")
            # The provider API is recent-N; fetch the span, then prove the day.
            limit = _ALL_RECORDS_LIMIT if bootstrap else age + 30
        else:
            limit = compute_limit(from_date, to_date)
        backfill_policy = message.metadata.get("backfillPolicy", {})
        data_policy = backfill_policy.get("dataPolicy")
        if data_policy is not None and data_policy not in (
            "PREFER_EXISTING",
            "REFRESH",
        ):
            raise ValueError("Unsupported backfill data policy")
        # Missing dataPolicy retains the old policy for already accepted commands.
        prefer_existing = data_policy == "PREFER_EXISTING" or (
            data_policy is None and backfill_policy.get("force") is True
        )
        reuse_eod = (
            prefer_existing
            and backfill_policy.get("verifyEodParquet") is True
            and message.business_date is not None
            and existing_df is not None
            and not existing_df.empty
            and "date" in existing_df
            and pd.to_datetime(existing_df["date"], errors="raise")
            .dt.date.eq(message.business_date)
            .any()
        )
        new_df = (
            existing_df.copy()
            if reuse_eod
            else await fetch_stock_data(client, code, limit)
        )
        if message.business_date is not None:
            new_df = normalize_stock_price_dataframe_columns(new_df)
            if bootstrap:
                # Restore lookback for cold/new symbols without admitting future rows.
                new_df = cutoff_frame(new_df, message.business_date, require_day=True)
            else:
                new_df = day_rows(new_df, message.business_date)

        frames = [df for df in (existing_df, new_df) if df is not None and not df.empty]
        combined = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()

        if not combined.empty:
            combined = normalize_stock_price_dataframe_columns(combined)
            combined = normalize_date_contracts(combined)
            combined = combined.drop_duplicates(
                subset=["date"], keep="last" if message.business_date else "first"
            )
            combined = combined.sort_values("date")

        snapshot_options = {}
        if getattr(settings, "output_snapshots_enabled", False) is True:
            snapshot_options["snapshot_identity"] = OutputSnapshotIdentity(
                "eod", {"exchange": exchange.lower(), "code": code.lower()}
            )
        with capture_output_publication(
            payload, getattr(settings, "output_snapshots_enabled", False) is True
        ) as publication:
            await parquet_storage.write_dataframe(
                object_name, combined, **snapshot_options
            )
            if publication.contract is not None:
                await publication.publish(status_publisher.producer)

        status = build_status(
            payload,
            started_at,
            # TODO(TD-005): Distinguish valid-empty work from missing/invalid output.
            # Ref: docs/technical-debt/005-job-status-empty-output-semantics.md
            JobStatus.SUCCESS,
            records_inserted=0 if reuse_eod else len(new_df),
            total_records=len(combined),
            new_offset=to_date.isoformat(),
        )
    except Exception as exc:
        logger.exception("Failed to process stock-price sync message: %s", exc)
        status = build_status(
            payload,
            started_at,
            JobStatus.ERROR,
            error_message=str(exc),
        )

    await publish_terminal_status(
        getattr(status_publisher, "producer", None),
        status_publisher.publish,
        status,
        payload,
        publication,
    )
    return status
