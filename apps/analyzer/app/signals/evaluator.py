from __future__ import annotations

from typing import Any

import pandas as pd
from py_common.storage.atomic_work import cutoff_frame
from py_common.storage.output_readiness import read_input_document
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalEvaluationJobMessage
from app.signals.storage import SignalHistoryRepository, SignalOutcomeEvaluation


class SignalOutcomeEvaluator:
    """Evaluate actual outcomes for shared signal history files."""

    def __init__(
        self,
        settings: AppSettings,
        parquet_storage: ParquetStorage,
        metadata_reader=None,
    ) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._metadata_reader = metadata_reader
        self._history_repository = SignalHistoryRepository(
            parquet_storage,
            snapshots_enabled=getattr(settings, "output_snapshots_enabled", False)
            is True,
        )

    async def evaluate(self, payload: dict[str, Any]) -> SignalOutcomeEvaluation:
        message = SignalEvaluationJobMessage.model_validate(payload)
        history_path = self._settings.stock_data_paths.signal_history(
            message.strategy,
            message.timeframe,
            message.exchange,
        )
        bound_inputs = (
            "outputCompletion" in message.metadata
            or getattr(self._settings, "output_readiness_mode", "manifest") == "postgres"
        )
        document = None
        history_loader = None
        eligible_rows = None
        if bound_inputs:
            if self._metadata_reader is None:
                raise ValueError("PostgreSQL input reader unavailable")
            document = await read_input_document(self._metadata_reader, message.metadata)
            signals = document.resolve(
                "signals",
                {
                    "strategy": message.strategy.lower(),
                    "timeframe": message.timeframe.lower(),
                    "exchange": message.exchange.lower(),
                },
            )
            if signals is None or signals.path != history_path:
                raise ValueError("Signal history is not READY at the canonical path")

            async def history_loader() -> pd.DataFrame:
                return await self._parquet_storage.read_bound_dataframe(
                    "signals", signals, signals.dataVersion
                )

            scopes = (
                [proof["workScope"] for proof in signals.workProofs]
                if getattr(signals, "workProofs", None)
                else [signals.workScope] if getattr(signals, "workScope", None) else []
            )
            if scopes:
                eligible_rows = frozenset(
                    (key["symbol_key"], key["signal_date"], key.get("model_version"))
                    for scope in scopes
                    for key in scope["rowKeys"]
                )

        async def _load_eod(symbol_key: str) -> pd.DataFrame:
            exchange, code = symbol_key.split("-", maxsplit=1)
            eod_path = self._settings.stock_data_paths.eod(exchange, code)
            if document is not None:
                manifest = document.resolve(
                    "eod", {"exchange": exchange.lower(), "code": code.lower()}
                )
                if manifest is None:
                    raise ValueError("EOD partition is not READY")
                frame = await self._parquet_storage.read_bound_dataframe(
                    "eod", manifest, manifest.dataVersion
                )
            else:
                frame = await self._parquet_storage.read_dataframe(eod_path)
            return cutoff_frame(frame, message.business_date)

        return await self._history_repository.update_outcomes(
            history_path,
            _load_eod,
            history_loader=history_loader,
            eligible_rows=eligible_rows,
        )
