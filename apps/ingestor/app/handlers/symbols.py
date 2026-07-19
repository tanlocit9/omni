import json
import logging
import math
from datetime import UTC, datetime
from typing import Any

import pandas as pd
from aiokafka import AIOKafkaProducer

from app.messaging.status import build_status
from app.settings import settings
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client
from app.stocks.sectors_cache import get_cached_sectors
from app.storage.minio_client import get_minio_client
from app.storage.parquet import read_existing_parquet, write_parquet_to_minio

logger = logging.getLogger(__name__)

CLASSIFICATION_COLUMNS = [
    "sectorCode",
    "sectorTaxonomy",
    "sectorLevel",
    "sourceSectorCode",
    "sourceSectorNameVi",
    "sourceSectorNameEn",
    "icbLv1Code",
    "icbLv1NameVi",
    "icbLv1NameEn",
    "icbLv2Code",
    "icbLv2NameVi",
    "icbLv2NameEn",
    "icbLv3Code",
    "icbLv3NameVi",
    "icbLv3NameEn",
    "icbLv4Code",
    "icbLv4NameVi",
    "icbLv4NameEn",
    "classificationUpdatedAt",
]


async def process_sync_symbols_message(
    raw_msg: bytes,
    producer: AIOKafkaProducer,
    default_client: StockClient,
) -> None:
    started_at = datetime.now(UTC)
    payload: dict[str, Any] = {}
    exchange = None

    try:
        payload = json.loads(raw_msg.decode())
        exchange = payload["exchange"]
        metadata = payload.get("metadata") or {}
        expected_count = metadata.get("symbolCount")
        include_sector = bool(metadata.get("includeSectorClassification", False))
        bucket = metadata.get("bucket")
        object_name_override = metadata.get("objectName")

        source = payload.get("source")
        client = get_or_create_client(source) if source else default_client

        symbols = await client.fetch_symbols(exchange=exchange)
        symbols_df = pd.DataFrame(symbols)

        symbols_df = validate_symbols_snapshot(symbols_df, exchange)

        minio = get_minio_client()
        object_name = object_name_override or settings.get_symbols_path(exchange)
        previous_df = read_existing_parquet(minio, object_name, bucket=bucket)

        warnings: list[str] = []
        classification_source = "NONE"
        sectors: dict[str, dict[str, Any]] = {}

        if include_sector:
            try:
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

        write_parquet_to_minio(minio, canonical_df, object_name, bucket=bucket)

        active_df = canonical_df
        if "delistedDate" in active_df.columns:
            active_df = active_df[active_df["delistedDate"].isna()]

        current_count = len(active_df)

        await publish_symbol_upsert_batch(
            producer,
            job_definition_id=payload.get("jobDefinitionId") or payload.get("jobId"),
            execution_id=payload.get("executionId") or payload.get("logId"),
            parent_execution_id=payload.get("parentExecutionId"),
            exchange=exchange,
            merged_df=active_df,
            expected_count=expected_count or current_count,
        )

        status = build_status(
            "exchange",
            exchange,
            payload,
            started_at,
            "partial_success" if warnings else "success",
            records_inserted=current_count,
            total_records=current_count,
        )
        if warnings:
            status["warnings"] = warnings
        status["classificationSource"] = classification_source
    except Exception as exc:
        logger.exception("Failed to process sync-symbols message: %s", exc)
        status = build_status(
            "exchange",
            exchange,
            payload,
            started_at,
            "error",
            error_message=str(exc),
        )

    await producer.send_and_wait(
        settings.sync_job_status_topic,
        key=status["exchange"].encode() if status.get("exchange") else None,
        value=json.dumps(status, default=str).encode(),
    )


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
        df["classificationUpdatedAt"] = datetime.now(UTC).isoformat()
    else:
        preserve_previous_classification(df, previous_df)

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
    mappings = metadata.get("sectorMappings") or []
    mapping_by_source_code = {
        str(row.get("sourceCode")): row.get("canonicalCode")
        for row in mappings
        if row.get("sourceCode") and row.get("canonicalCode")
    }

    source_code_column = f"icbLv{level}Code"
    source_name_vi_column = f"icbLv{level}NameVi"
    source_name_en_column = f"icbLv{level}NameEn"

    df["sectorTaxonomy"] = taxonomy
    df["sectorLevel"] = level
    df["sourceSectorCode"] = df.get(source_code_column)
    df["sourceSectorNameVi"] = df.get(source_name_vi_column)
    df["sourceSectorNameEn"] = df.get(source_name_en_column)
    df["sectorCode"] = df["sourceSectorCode"].map(
        lambda source_code: (
            mapping_by_source_code.get(str(source_code))
            if pd.notna(source_code)
            else None
        )
    )


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
    mapped_count = int(df["sectorCode"].notna().sum()) if "sectorCode" in df else 0
    unmatched_count = max(vnd_count - matched_count, 0)
    match_percentage = (matched_count / vnd_count * 100) if vnd_count else 100.0
    return {
        "vndSymbols": vnd_count,
        "icbSymbols": icb_count,
        "matchedCount": matched_count,
        "unmatchedCount": unmatched_count,
        "matchPercentage": match_percentage,
        "mappedCanonicalCount": mapped_count,
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
        "type": _clean_value(row.get("type") or row.get("comTypeCode")),
        "status": _clean_value(row.get("status")),
        "isin": _clean_value(row.get("isin")),
        "companyId": _clean_value(row.get("companyId") or row.get("organCode")),
        "companyName": _clean_value(row.get("companyName") or row.get("organName")),
        "listedDate": _clean_value(row.get("listedDate")),
        "sectorCode": _clean_value(row.get("sectorCode")),
        "sectorTaxonomy": _clean_value(row.get("sectorTaxonomy")),
        "sectorLevel": _clean_value(row.get("sectorLevel")),
        "sourceSectorCode": _clean_value(row.get("sourceSectorCode")),
        "sourceSectorNameVi": _clean_value(row.get("sourceSectorNameVi")),
        "sourceSectorNameEn": _clean_value(row.get("sourceSectorNameEn")),
        "icbLv1Code": _clean_value(row.get("icbLv1Code")),
        "icbLv1NameVi": _clean_value(row.get("icbLv1NameVi")),
        "icbLv1NameEn": _clean_value(row.get("icbLv1NameEn")),
        "icbLv2Code": _clean_value(row.get("icbLv2Code")),
        "icbLv2NameVi": _clean_value(row.get("icbLv2NameVi")),
        "icbLv2NameEn": _clean_value(row.get("icbLv2NameEn")),
        "icbLv3Code": _clean_value(row.get("icbLv3Code")),
        "icbLv3NameVi": _clean_value(row.get("icbLv3NameVi")),
        "icbLv3NameEn": _clean_value(row.get("icbLv3NameEn")),
        "icbLv4Code": _clean_value(row.get("icbLv4Code")),
        "icbLv4NameVi": _clean_value(row.get("icbLv4NameVi")),
        "icbLv4NameEn": _clean_value(row.get("icbLv4NameEn")),
        "classificationUpdatedAt": _clean_value(row.get("classificationUpdatedAt")),
        "meta": meta,
    }


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
