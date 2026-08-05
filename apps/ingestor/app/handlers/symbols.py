import json
import logging
import math
from datetime import UTC, datetime
from typing import Any

import pandas as pd
from aiokafka import AIOKafkaProducer
from py_common.kafka import decode_json_object_payload
from py_common.messaging import JobStatus, JobStatusMessage, JobStatusPublisher, utc_now
from py_common.storage.parquet import ParquetStorage

from app.messaging.messages import SyncSymbolsJobMessage
from app.messaging.status import build_status, status_publish_key
from app.settings import settings
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client
from app.stocks.sectors_cache import get_cached_sectors

logger = logging.getLogger(__name__)

CLASSIFICATION_COLUMNS = [
    "sectorTaxonomy",
    "sectorLevel",
    "sourceSectorCode",
    "sourceSectorNameVi",
    "sourceSectorNameEn",
    "icbLv1Code",
    "icbLv1NameVi",
    "icbLv1NameEn",
    "sectorLv1Code",
    "icbLv2Code",
    "icbLv2NameVi",
    "icbLv2NameEn",
    "sectorLv2Code",
    "icbLv3Code",
    "icbLv3NameVi",
    "icbLv3NameEn",
    "sectorLv3Code",
    "icbLv4Code",
    "icbLv4NameVi",
    "icbLv4NameEn",
    "sectorLv4Code",
    "classificationUpdatedAt",
]


async def process_sync_symbols_message(
    raw_msg: str | bytes | dict[str, Any],
    producer: AIOKafkaProducer,
    status_publisher: JobStatusPublisher,
    default_client: StockClient,
    parquet_storage: ParquetStorage,
) -> JobStatusMessage:
    started_at = utc_now()
    payload: dict[str, Any] = {}
    exchange = None

    try:
        payload = decode_json_object_payload(raw_msg, "Symbols sync job")
        message = SyncSymbolsJobMessage.model_validate(payload)
        payload = message.status_payload
        exchange = message.exchange
        metadata = message.metadata
        expected_count = message.expected_count
        include_sector = message.include_sector_classification

        client = (
            get_or_create_client(message.source) if message.source else default_client
        )

        symbols = await client.fetch_symbols(exchange=exchange)
        symbols_df = pd.DataFrame(symbols)
        log_symbols_snapshot_diagnostics(
            symbols_df,
            exchange=exchange,
            stage="fetched",
            expected_count=expected_count,
        )

        symbols_df = validate_symbols_snapshot(symbols_df, exchange)
        log_symbols_snapshot_diagnostics(
            symbols_df,
            exchange=exchange,
            stage="validated",
            expected_count=expected_count,
        )

        object_name = settings.get_symbols_path(exchange)
        previous_df = await parquet_storage.read_optional_dataframe(object_name)

        warnings: list[str] = []
        classification_source = "NONE"
        sectors: dict[str, dict[str, Any]] = {}

        if include_sector:
            try:
                logger.info("Fetching sector classification from VCI")
                vci_client = get_or_create_client("VCI")
                sectors = await get_cached_sectors(vci_client)
                classification_source = "FRESH"
            except Exception as exc:
                if previous_df.empty:
                    raise RuntimeError(
                        "VCI classification unavailable and no previous "
                        "symbol snapshot exists"
                    ) from exc
                warnings.append(
                    "VCI classification unavailable; reused previous snapshot"
                )
                classification_source = "STALE"
                logger.warning(
                    "VCI classification unavailable, reusing previous snapshot"
                )

        canonical_df = normalize_symbols_dataframe(
            symbols_df,
            exchange=exchange,
            metadata=metadata,
            sectors=sectors,
            previous_df=previous_df,
            include_sector=include_sector,
        )

        if include_sector and sectors:
            classification_metrics = calculate_classification_metrics(
                canonical_df, sectors
            )
            status_warning = validate_classification_coverage(classification_metrics)
            if status_warning:
                warnings.append(status_warning)

        await parquet_storage.write_dataframe(object_name, canonical_df)

        active_df = canonical_df
        if "delistedDate" in active_df.columns:
            delisted_mask = active_df["delistedDate"].notna()
            if delisted_mask.any():
                logger.warning(
                    "Keeping %d rows with delistedDate in symbol upsert for %s "
                    "to avoid false deactivation; sample=%s",
                    int(delisted_mask.sum()),
                    exchange,
                    active_df.loc[delisted_mask, ["exchange", "code", "delistedDate"]]
                    .head(20)
                    .to_dict("records"),
                )

        log_symbols_snapshot_diagnostics(
            active_df,
            exchange=exchange,
            stage="active",
            expected_count=expected_count,
        )
        current_count = len(active_df)

        job_definition_id = message.job_definition_id
        execution_id = message.execution_id
        parent_execution_id = message.parent_execution_id

        if include_sector:
            await publish_sector_upsert_batch(
                producer,
                job_definition_id=job_definition_id,
                execution_id=execution_id,
                parent_execution_id=parent_execution_id,
                exchange=exchange,
                merged_df=active_df,
                expected_count=expected_count or current_count,
            )

        await publish_symbol_upsert_batch(
            producer,
            job_definition_id=job_definition_id,
            execution_id=execution_id,
            parent_execution_id=parent_execution_id,
            exchange=exchange,
            merged_df=active_df,
            expected_count=expected_count or current_count,
        )

        status = build_status(
            "exchange",
            exchange,
            payload,
            started_at,
            JobStatus.PARTIAL_SUCCESS if warnings else JobStatus.SUCCESS,
            records_inserted=current_count,
            total_records=current_count,
        )
        status_extras: dict[str, Any] = {
            "classificationSource": classification_source,
        }
        if warnings:
            status_extras["warnings"] = warnings
        status = status.model_copy(update=status_extras)
    except Exception as exc:
        logger.exception("Failed to process sync-symbols message: %s", exc)
        status = build_status(
            "exchange",
            exchange,
            payload,
            started_at,
            JobStatus.ERROR,
            error_message=str(exc),
        )

    await status_publisher.publish(status, key=status_publish_key(status, "exchange"))
    return status


def validate_symbols_snapshot(symbols_df: pd.DataFrame, exchange: str) -> pd.DataFrame:
    required = {"code", "floor", "status"}
    missing = required - set(symbols_df.columns)
    if missing:
        raise ValueError(f"Missing required symbol fields: {sorted(missing)}")

    validated_df = symbols_df.copy()
    validated_df["_normalized_floor"] = (
        validated_df["floor"].dropna().astype(str).str.upper()
    )
    validated_df["_normalized_code"] = (
        validated_df["code"].dropna().astype(str).str.upper()
    )

    duplicated = validated_df.duplicated(
        subset=["_normalized_floor", "_normalized_code"],
        keep="first",
    )
    if duplicated.any():
        sample = (
            validated_df.loc[
                duplicated,
                ["_normalized_floor", "_normalized_code"],
            ]
            .rename(
                columns={
                    "_normalized_floor": "exchange",
                    "_normalized_code": "code",
                }
            )
            .head(10)
            .to_dict("records")
        )
        logger.warning(
            "Deduplicating %s duplicate (exchange, code) rows in symbol snapshot: %s",
            int(duplicated.sum()),
            sample,
        )
        validated_df = validated_df.loc[~duplicated].copy()

    exchange_values = set(validated_df["_normalized_floor"].dropna().unique())
    if exchange.upper() not in exchange_values:
        logger.warning(
            "Fetched symbol snapshot for %s but floor values are %s",
            exchange,
            sorted(exchange_values),
        )

    return validated_df.drop(columns=["_normalized_floor", "_normalized_code"])


def log_symbols_snapshot_diagnostics(
    symbols_df: pd.DataFrame,
    *,
    exchange: str,
    stage: str,
    expected_count: Any,
) -> None:
    row_count = len(symbols_df)
    unique_codes = (
        int(symbols_df["code"].dropna().astype(str).str.upper().nunique())
        if "code" in symbols_df.columns
        else 0
    )
    floor_counts = (
        symbols_df["floor"].dropna().astype(str).str.upper().value_counts().to_dict()
        if "floor" in symbols_df.columns
        else {}
    )
    exchange_counts = (
        symbols_df["exchange"].dropna().astype(str).str.upper().value_counts().to_dict()
        if "exchange" in symbols_df.columns
        else {}
    )
    status_counts = (
        symbols_df["status"].dropna().astype(str).str.upper().value_counts().to_dict()
        if "status" in symbols_df.columns
        else {}
    )
    delisted_count = (
        int(symbols_df["delistedDate"].notna().sum())
        if "delistedDate" in symbols_df.columns
        else 0
    )

    logger.warning(
        "Symbol snapshot diagnostics stage=%s exchange=%s rows=%d "
        "uniqueCodes=%d expected=%s floors=%s exchanges=%s statuses=%s "
        "delistedRows=%d",
        stage,
        exchange,
        row_count,
        unique_codes,
        expected_count,
        floor_counts,
        exchange_counts,
        status_counts,
        delisted_count,
    )

    if expected_count is not None and row_count != expected_count:
        logger.warning(
            "Symbol snapshot count mismatch stage=%s exchange=%s expected=%s "
            "actualRows=%d uniqueCodes=%d",
            stage,
            exchange,
            expected_count,
            row_count,
            unique_codes,
        )


def normalize_sector_value(value: Any) -> Any:
    if pd.isna(value):
        return None

    return (
        str(value)
        .strip()
        .upper()
        .replace("&", "AND")
        .replace("-", "_")
        .replace(" ", "_")
    )


def normalize_symbols_dataframe(
    symbols_df: pd.DataFrame,
    *,
    exchange: str,
    metadata: dict[str, Any],
    sectors: dict[str, dict[str, Any]],
    previous_df: pd.DataFrame,
    include_sector: bool,
) -> pd.DataFrame:
    df = symbols_df.copy()
    df["code"] = df["code"].astype(str).str.upper()
    df["exchange"] = df["floor"].astype(str).str.upper()

    if include_sector and sectors:
        sector_df = pd.DataFrame(sectors.values())
        if not sector_df.empty:
            sector_df = sector_df.rename(columns=_to_canonical_sector_column)
            df = df.merge(
                sector_df,
                left_on="code",
                right_on="symbol",
                how="left",
            )
            if "symbol" in df.columns:
                df = df.drop(columns=["symbol"])

            apply_canonical_sector_mapping(df, metadata)
            apply_sector_level_codes(df)
            df["classificationUpdatedAt"] = datetime.now(UTC).isoformat()
        else:
            preserve_previous_classification(df, previous_df)
            apply_sector_level_codes(df)

    for column in CLASSIFICATION_COLUMNS:
        if column not in df.columns:
            df[column] = None

    return df


def _to_canonical_sector_column(column: str) -> str:
    mapping = {
        "icb_lv1_code": "icbLv1Code",
        "icb_lv1_name_vi": "icbLv1NameVi",
        "icb_lv1_name_en": "icbLv1NameEn",
        "icb_lv2_code": "icbLv2Code",
        "icb_lv2_name_vi": "icbLv2NameVi",
        "icb_lv2_name_en": "icbLv2NameEn",
        "icb_lv3_code": "icbLv3Code",
        "icb_lv3_name_vi": "icbLv3NameVi",
        "icb_lv3_name_en": "icbLv3NameEn",
        "icb_lv4_code": "icbLv4Code",
        "icb_lv4_name_vi": "icbLv4NameVi",
        "icb_lv4_name_en": "icbLv4NameEn",
    }
    return mapping.get(column, column)


def apply_canonical_sector_mapping(df: pd.DataFrame, metadata: dict[str, Any]) -> None:
    taxonomy = str(metadata.get("sectorTaxonomy", "ICB")).upper()
    level = int(metadata.get("sectorLevel", 3))

    source_code_column = f"icbLv{level}Code"
    source_name_vi_column = f"icbLv{level}NameVi"
    source_name_en_column = f"icbLv{level}NameEn"

    df["sectorTaxonomy"] = taxonomy
    df["sectorLevel"] = level
    df["sourceSectorCode"] = df.get(source_code_column)
    df["sourceSectorNameVi"] = df.get(source_name_vi_column)
    df["sourceSectorNameEn"] = df.get(source_name_en_column)


def apply_sector_level_codes(df: pd.DataFrame) -> None:
    for level in range(1, 5):
        source_column = f"icbLv{level}NameEn"
        target_column = f"sectorLv{level}Code"
        if source_column in df.columns:
            df[target_column] = df[source_column].map(normalize_sector_value)


def preserve_previous_classification(
    df: pd.DataFrame, previous_df: pd.DataFrame
) -> None:
    if previous_df.empty:
        return

    previous = previous_df.copy()
    if "exchange" not in previous.columns and "floor" in previous.columns:
        previous["exchange"] = previous["floor"]
    if "code" not in previous.columns or "exchange" not in previous.columns:
        return

    previous["code"] = previous["code"].astype(str).str.upper()
    previous["exchange"] = previous["exchange"].astype(str).str.upper()
    available_columns = [
        column for column in CLASSIFICATION_COLUMNS if column in previous.columns
    ]
    merge_columns = ["exchange", "code", *available_columns]
    if len(merge_columns) <= 2:
        return

    merged = df.merge(previous[merge_columns], on=["exchange", "code"], how="left")
    for column in available_columns:
        df[column] = merged[column]


def calculate_classification_metrics(
    df: pd.DataFrame, sectors: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    vnd_count = len(df)
    icb_count = len(sectors)
    matched_count = (
        int(df["sourceSectorCode"].notna().sum()) if "sourceSectorCode" in df else 0
    )
    unmatched_count = max(vnd_count - matched_count, 0)
    match_percentage = (matched_count / vnd_count * 100) if vnd_count else 100.0
    return {
        "vndSymbols": vnd_count,
        "icbSymbols": icb_count,
        "matchedCount": matched_count,
        "unmatchedCount": unmatched_count,
        "matchPercentage": match_percentage,
    }


def validate_classification_coverage(metrics: dict[str, Any]) -> str | None:
    match_percentage = float(metrics["matchPercentage"])
    if match_percentage < 90:
        raise ValueError(
            "Sector classification coverage below failure threshold: "
            f"{match_percentage:.2f}%"
        )
    if match_percentage < 98:
        return (
            "Sector classification coverage below warning threshold: "
            f"{match_percentage:.2f}%"
        )
    return None


def _clean_str(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    text = str(value).strip()
    return text or None


def _clean_value(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    if pd.isna(value):
        return None
    if isinstance(value, pd.Timestamp):
        return value.date().isoformat()
    return value


def _row_to_symbol_record(row: pd.Series) -> dict[str, Any] | None:
    code = _clean_str(row.get("code"))
    exchange = _clean_str(row.get("exchange") or row.get("floor"))

    if not code or not exchange:
        logger.warning("Skipping row with missing code/exchange: %r", row.to_dict())
        return None

    meta_exclude = {"code", "exchange", *CLASSIFICATION_COLUMNS}
    meta = {
        key: _clean_value(value)
        for key, value in row.to_dict().items()
        if key not in meta_exclude and _clean_value(value) is not None
    }

    return {
        "code": code,
        "exchange": exchange,
        "companyId": _clean_value(row.get("companyId") or row.get("organCode")),
        "companyName": _clean_value(row.get("companyName") or row.get("organName")),
        "listedDate": _clean_value(row.get("listedDate")),
        "sectorTaxonomy": _clean_value(row.get("sectorTaxonomy")),
        "sectorLevel": _clean_value(row.get("sectorLevel")),
        "sourceSectorCode": _clean_value(row.get("sourceSectorCode")),
        "sectorLv1Code": _clean_value(row.get("sectorLv1Code")),
        "sectorLv2Code": _clean_value(row.get("sectorLv2Code")),
        "sectorLv3Code": _clean_value(row.get("sectorLv3Code")),
        "sectorLv4Code": _clean_value(row.get("sectorLv4Code")),
        "classificationUpdatedAt": _clean_value(row.get("classificationUpdatedAt")),
        "meta": meta,
    }


def _row_to_sector_records(row: pd.Series) -> list[dict[str, Any]]:
    sector_taxonomy = _clean_str(row.get("sectorTaxonomy"))
    classification_updated_at = _clean_value(row.get("classificationUpdatedAt"))
    records: list[dict[str, Any]] = []

    for level in range(1, 5):
        sector_code = _clean_str(row.get(f"sectorLv{level}Code"))
        if not sector_code:
            continue

        records.append(
            {
                "sectorCode": sector_code,
                "sectorTaxonomy": sector_taxonomy,
                "sectorLevel": level,
                "sourceSectorCode": _clean_str(row.get(f"icbLv{level}Code")),
                "sourceSectorNameVi": _clean_value(row.get(f"icbLv{level}NameVi")),
                "sourceSectorNameEn": _clean_value(row.get(f"icbLv{level}NameEn")),
                "classificationUpdatedAt": classification_updated_at,
                "meta": {
                    "source": "sync-symbols",
                    "sourceSymbolCode": _clean_value(row.get("code")),
                    "sourceSymbolExchange": _clean_value(row.get("exchange")),
                },
            }
        )

    return records


def _dedup_sector_records(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[Any, Any, Any]] = set()
    deduped: list[dict[str, Any]] = []

    for rec in records:
        key = (rec["sectorCode"], rec["sectorTaxonomy"], rec["sectorLevel"])
        if key in seen:
            continue
        seen.add(key)
        deduped.append(rec)

    return deduped


def _sector_records_from_dataframe(merged_df: pd.DataFrame) -> list[dict[str, Any]]:
    records_by_key: dict[tuple[Any, Any, Any], dict[str, Any]] = {}
    for _, row in merged_df.iterrows():
        for record in _row_to_sector_records(row):
            key = (
                record["sectorCode"],
                record["sectorTaxonomy"],
                record["sectorLevel"],
            )
            existing = records_by_key.get(key)
            if existing is None:
                records_by_key[key] = record
                continue

            for field, value in record.items():
                if field == "meta":
                    existing["meta"] = {**existing.get("meta", {}), **value}
                elif existing.get(field) is None and value is not None:
                    existing[field] = value

    return list(records_by_key.values())


async def publish_sector_upsert_batch(
    producer: AIOKafkaProducer,
    *,
    job_definition_id: str | None,
    execution_id: str | None,
    parent_execution_id: str | None,
    exchange: str,
    merged_df: pd.DataFrame,
    expected_count: int,
) -> None:
    records = _sector_records_from_dataframe(merged_df)

    if not records:
        logger.warning("No sector records to publish for %s", exchange)
        return

    effective_parent_execution_id = parent_execution_id or execution_id

    event = {
        "jobDefinitionId": job_definition_id,
        "executionId": execution_id,
        "parentExecutionId": effective_parent_execution_id,
        "exchange": exchange,
        "expectedCount": expected_count,
        "actualCount": len(records),
        "sectors": records,
        "detectedAt": datetime.now(UTC).isoformat(),
    }

    logger.warning(
        "Publishing sector upsert batch for %s: expected=%d actual=%d",
        exchange,
        expected_count,
        len(records),
    )

    result = await producer.send_and_wait(
        settings.topic_upsert_sectors,
        key=exchange.encode(),
        value=json.dumps(event, default=str).encode(),
    )

    logger.warning(
        "Published sector upsert batch for %s to topic=%s partition=%s offset=%s",
        exchange,
        result.topic,
        result.partition,
        result.offset,
    )


async def publish_symbol_upsert_batch(
    producer: AIOKafkaProducer,
    *,
    job_definition_id: str | None,
    execution_id: str | None,
    parent_execution_id: str | None,
    exchange: str,
    merged_df: pd.DataFrame,
    expected_count: int,
) -> None:
    records = [
        rec
        for rec in (_row_to_symbol_record(row) for _, row in merged_df.iterrows())
        if rec is not None
    ]

    effective_parent_execution_id = parent_execution_id or execution_id

    event = {
        "jobDefinitionId": job_definition_id,
        "executionId": execution_id,
        "parentExecutionId": effective_parent_execution_id,
        "exchange": exchange,
        "expectedCount": expected_count,
        "actualCount": len(records),
        "symbols": records,
        "detectedAt": datetime.now(UTC).isoformat(),
    }

    logger.warning(
        "Publishing symbol upsert batch for %s: expected=%d actual=%d",
        exchange,
        expected_count,
        len(records),
    )

    result = await producer.send_and_wait(
        settings.topic_upsert_symbols,
        key=exchange.encode(),
        value=json.dumps(event, default=str).encode(),
    )

    logger.warning(
        "Published symbol upsert batch for %s to topic=%s partition=%s offset=%s",
        exchange,
        result.topic,
        result.partition,
        result.offset,
    )
