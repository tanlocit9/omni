from __future__ import annotations

import logging
from typing import Any

from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.manifest import (
    DatasetInput,
    ManifestReader,
    ManifestWriter,
    publish_dataset_manifest,
)
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalHistoryRepository, SignalTransition
from app.signals.strategy import (
    ICHIMOKU_V1,
    TREND_MOMENTUM_V1,
    MarketSignal,
    SignalResult,
    calculate_ichimoku_v1,
    calculate_trend_momentum_v1,
)

_logger = logging.getLogger(__name__)


class SignalJobHandler:
    """Process versioned market-signal strategies using shared Parquet storage."""

    def __init__(
        self,
        settings: AppSettings,
        parquet_storage: ParquetStorage,
        manifest_reader: ManifestReader | None = None,
        manifest_writer: ManifestWriter | None = None,
    ) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._manifest_reader = manifest_reader
        self._manifest_writer = manifest_writer
        self._signal_repository = SignalHistoryRepository(parquet_storage)

    async def handle(self, payload: dict[str, Any]) -> SignalTransition:
        message = SignalJobMessage.model_validate(payload)
        exchange, code = message.parse_symbol_key()

        eod_partition = {"exchange": exchange.lower(), "code": code.lower()}
        indicator_partition = {
            "source": "ad_close",
            "timeframe": message.timeframe,
            **eod_partition,
        }
        eod_path = self._settings.stock_data_paths.eod(exchange, code)
        indicators_path = self._settings.stock_data_paths.indicators(
            "ad_close",
            message.timeframe,
            exchange,
            code,
        )
        history_path = self._settings.stock_data_paths.signal_history(
            message.strategy,
            message.timeframe,
            exchange,
        )
        current_path = self._settings.stock_data_paths.signal_current(
            message.strategy,
            message.timeframe,
            exchange,
            code,
        )

        _logger.info(
            "Calculating signal symbolKey=%s timeframe=%s strategy=%s "
            "eodPath=%s indicatorsPath=%s",
            message.symbol_key,
            message.timeframe,
            message.strategy,
            eod_path,
            indicators_path,
        )
        eod_manifest = None
        indicator_manifest = None
        if self._manifest_reader is not None:
            eod_manifest = await self._manifest_reader.read_manifest(
                "eod", eod_partition
            )
            indicator_manifest = await self._manifest_reader.read_manifest(
                "indicators", indicator_partition
            )
            if eod_manifest.status != "READY" or indicator_manifest.status != "READY":
                raise ManifestInvalidError("Signal inputs must have READY manifests")
            eod_path = eod_manifest.path
            indicators_path = indicator_manifest.path

        eod_frame = await self._parquet_storage.read_dataframe(eod_path)
        try:
            indicators_frame = await self._parquet_storage.read_dataframe(
                indicators_path
            )
        except StorageObjectNotFoundError as exc:
            _logger.warning(
                "Skipping signal calculation because prerequisite indicator object "
                "is missing symbolKey=%s timeframe=%s strategy=%s bucket=%s "
                "objectName=%s; verify the indicator job completed for this symbol "
                "before signal dispatch",
                message.symbol_key,
                message.timeframe,
                message.strategy,
                exc.bucket,
                exc.object_name,
            )
            result = SignalResult(
                signal=MarketSignal.NO_DECISION,
                price=None,
                signal_date=None,
                reason_codes=["MISSING_INDICATOR_OBJECT"],
                score=0,
                strategy=message.strategy,
            )
        else:
            if message.strategy == TREND_MOMENTUM_V1:
                result = calculate_trend_momentum_v1(eod_frame, indicators_frame)
            elif message.strategy == ICHIMOKU_V1:
                result = calculate_ichimoku_v1(eod_frame, indicators_frame)
            else:
                raise ValueError(f"Unsupported signal strategy: {message.strategy}")

        async def publish_manifest(history_frame, write_result) -> None:
            if self._manifest_writer is None:
                return
            await publish_dataset_manifest(
                writer=self._manifest_writer,
                dataset="signals",
                partition={
                    "strategy": message.strategy.lower(),
                    "timeframe": message.timeframe,
                    "exchange": exchange.lower(),
                },
                data_path=history_path,
                dataframe=history_frame,
                object_checksums=[(write_result.object_name, write_result.checksum)],
                inputs=self._lineage_inputs(history_frame),
                execution_id=str(message.execution_id),
                total_bytes=write_result.total_bytes,
            )

        return await self._signal_repository.persist_transition(
            history_path,
            current_path,
            message.symbol_key,
            message.timeframe,
            result,
            exchange=exchange,
            eod_data_version=eod_manifest.dataVersion if eod_manifest else None,
            indicators_data_version=(
                indicator_manifest.dataVersion if indicator_manifest else None
            ),
            after_persist=publish_manifest,
        )

    @staticmethod
    def _lineage_inputs(history_frame) -> list[DatasetInput]:
        inputs: dict[tuple[str, tuple[tuple[str, str], ...], str], DatasetInput] = {}
        for row in history_frame.to_dict("records"):
            symbol_parts = str(row["symbol_key"]).split("-", maxsplit=1)
            if len(symbol_parts) != 2:
                continue
            exchange, code = (part.lower() for part in symbol_parts)
            partitions = {
                "eod": {"exchange": exchange, "code": code},
                "indicators": {
                    "source": "ad_close",
                    "timeframe": str(row["timeframe"]),
                    "exchange": exchange,
                    "code": code,
                },
            }
            for dataset, version_column in (
                ("eod", "eod_data_version"),
                ("indicators", "indicators_data_version"),
            ):
                version = row.get(version_column)
                if not isinstance(version, str) or not version:
                    continue
                item = DatasetInput(dataset, partitions[dataset], version)
                key = (dataset, tuple(item.partition.items()), version)
                inputs[key] = item
        return list(inputs.values())
