"""S3 path construction for stock data."""

from dataclasses import dataclass

from py_common.config.constants import Timeframe, validate_indicator_timeframe


@dataclass(frozen=True)
class StockDataPaths:
    """S3 path builder for stock data bucket.

    All paths follow lowercase normalization rules:
    - Exchange names: HOSE → hose, HNX → hnx, UPCOM → upcom
    - Ticker codes: HPG → hpg, FPT → fpt
    - Timeframes: validated against Timeframe enum

    Attributes:
        symbols_base: Base path for symbol metadata
        symbols_pattern: Pattern for symbol file paths
        eod_base: Base path for EOD price data
        eod_pattern: Pattern for EOD file paths
        indicators_base: Base path for indicator data
        indicators_pattern: Pattern for indicator file paths

    Examples:
        >>> paths = StockDataPaths(
        ...     symbols_base="symbols/",
        ...     symbols_pattern="{exchange}.parquet",
        ...     eod_base="eod/",
        ...     eod_pattern="{exchange}/{code}.parquet",
        ...     indicators_base="indicators/",
        ...     indicators_pattern="{source}/{timeframe}/{exchange}/{code}.parquet"
        ... )
        >>> paths.symbols("HOSE")
        'symbols/hose.parquet'
        >>> paths.eod("HOSE", "HPG")
        'eod/hose/hpg.parquet'
        >>> paths.indicators("close", Timeframe.ONE_DAY, "HOSE", "HPG")
        'indicators/close/1d/hose/hpg.parquet'
    """

    symbols_base: str
    symbols_pattern: str
    eod_base: str
    eod_pattern: str
    indicators_base: str
    indicators_pattern: str
    signals_base: str = "signals/"
    signals_pattern: str = "{strategy}/{timeframe}/{exchange}.parquet"
    signal_current_base: str = "signals/"
    signal_current_pattern: str = "{strategy}/{timeframe}/{exchange}.parquet"
    symbol_features_base: str = "features/symbol/"
    symbol_features_pattern: str = "{timeframe}/{exchange}/{code}.parquet"
    sector_features_base: str = "features/sector/"
    sector_features_pattern: str = "{timeframe}/lv{sector_level}/{sector_code}.parquet"
    sector_rotation_backtests_base: str = "backtests/sector-rotation/"
    sector_rotation_backtests_pattern: str = (
        "{strategy}/{timeframe}/lv{sector_level}.parquet"
    )
    sector_transition_predictions_base: str = "research/sector-transition/predictions/"
    sector_transition_predictions_pattern: str = (
        "{strategy}/{timeframe}/lv{sector_level}.parquet"
    )
    sector_transition_decisions_base: str = "research/sector-transition/decisions/"
    sector_transition_decisions_pattern: str = (
        "{strategy}/{timeframe}/lv{sector_level}.parquet"
    )
    sector_transition_probabilities_base: str = "research/sector-transition/probabilities/"
    sector_transition_probabilities_pattern: str = (
        "{strategy}/{timeframe}/lv{sector_level}.parquet"
    )
    sector_transition_outcomes_base: str = "research/sector-transition/outcomes/"
    sector_transition_outcomes_pattern: str = (
        "{strategy}/{timeframe}/lv{sector_level}.parquet"
    )

    @staticmethod
    def _normalize_path_part(value: str | None, name: str) -> str:
        """Normalize one S3 path component and reject malformed values."""
        if value is None:
            raise ValueError(f"{name} must not be None")
        normalized = value.strip().lower()
        if not normalized:
            raise ValueError(f"{name} must not be empty or whitespace-only")
        return normalized

    def symbols(self, exchange: str) -> str:
        """Build S3 path for symbol metadata file.

        Args:
            exchange: Exchange name (will be normalized to lowercase)

        Returns:
            Path like: symbols/hose.parquet

        Examples:
            >>> paths.symbols("HOSE")
            'symbols/hose.parquet'
            >>> paths.symbols("hnx")
            'symbols/hnx.parquet'
        """
        return self.symbols_base + self.symbols_pattern.format(
            exchange=self._normalize_path_part(exchange, "exchange")
        )

    def eod(self, exchange: str, code: str) -> str:
        """Build S3 path for EOD price data file.

        Args:
            exchange: Exchange name (will be normalized to lowercase)
            code: Stock ticker code (will be normalized to lowercase)

        Returns:
            Path like: eod/hose/hpg.parquet

        Examples:
            >>> paths.eod("HOSE", "HPG")
            'eod/hose/hpg.parquet'
            >>> paths.eod("hnx", "shs")
            'eod/hnx/shs.parquet'
        """
        return self.eod_base + self.eod_pattern.format(
            exchange=self._normalize_path_part(exchange, "exchange"),
            code=self._normalize_path_part(code, "code"),
        )

    def indicators(
        self, source: str, timeframe: Timeframe | str, exchange: str, code: str
    ) -> str:
        """Build S3 path for indicator data file.

        Validates timeframe against allowed values and normalizes source/exchange/code
        to lowercase.

        Args:
            source: Indicator source column (will be normalized to lowercase)
            timeframe: Timeframe interval (Timeframe enum or string)
            exchange: Exchange name (will be normalized to lowercase)
            code: Stock ticker code (will be normalized to lowercase)

        Returns:
            Path like: indicators/close/1d/hose/hpg.parquet

        Raises:
            ValueError: If timeframe is not a valid Timeframe value

        Examples:
            >>> paths.indicators("close", Timeframe.ONE_DAY, "HOSE", "HPG")
            'indicators/close/1d/hose/hpg.parquet'
            >>> paths.indicators("close", "1d", "HNX", "SHS")
            'indicators/close/1d/hnx/shs.parquet'
            >>> paths.indicators("close", "invalid", "HOSE", "HPG")
            Traceback (most recent call last):
                ...
            ValueError: Invalid timeframe 'invalid'.
            Must be one of: 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M
        """
        timeframe = validate_indicator_timeframe(timeframe)

        return self.indicators_base + self.indicators_pattern.format(
            source=self._normalize_path_part(source, "source"),
            timeframe=timeframe.value,
            exchange=self._normalize_path_part(exchange, "exchange"),
            code=self._normalize_path_part(code, "code"),
        )

    def signals(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        exchange: str,
        code: str | None = None,
    ) -> str:
        """Build S3 path for shared market signal history data file."""
        return self.signal_history(strategy, timeframe, exchange, code)

    def signal_history(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        exchange: str,
        code: str | None = None,
    ) -> str:
        """Build S3 path for shared market signal history data file."""
        return self._signal_path(
            base=self.signals_base,
            pattern=self.signals_pattern,
            strategy=strategy,
            timeframe=timeframe,
            exchange=exchange,
            code=code,
        )

    def signal_current(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        exchange: str,
        code: str | None = None,
    ) -> str:
        """Build S3 path for shared latest market signal state data file."""
        return self._signal_path(
            base=self.signal_current_base,
            pattern=self.signal_current_pattern,
            strategy=strategy,
            timeframe=timeframe,
            exchange=exchange,
            code=code,
        )

    def _signal_path(
        self,
        *,
        base: str,
        pattern: str,
        strategy: str,
        timeframe: Timeframe | str,
        exchange: str,
        code: str | None,
    ) -> str:
        timeframe = validate_indicator_timeframe(timeframe)
        values = {
            "strategy": self._normalize_path_part(strategy, "strategy"),
            "timeframe": timeframe.value,
            "exchange": self._normalize_path_part(exchange, "exchange"),
        }
        if "{code}" in pattern:
            values["code"] = self._normalize_path_part(code, "code")
        return base + pattern.format(**values)

    def symbol_features(
        self, timeframe: Timeframe | str, exchange: str, code: str
    ) -> str:
        """Build S3 path for symbol-level precomputed feature data."""
        timeframe = validate_indicator_timeframe(timeframe)
        return self.symbol_features_base + self.symbol_features_pattern.format(
            timeframe=timeframe.value,
            exchange=self._normalize_path_part(exchange, "exchange"),
            code=self._normalize_path_part(code, "code"),
        )

    def sector_features(
        self,
        timeframe: Timeframe | str,
        sector_level: int,
        sector_code: str,
    ) -> str:
        """Build S3 path for sector-level precomputed feature data."""
        timeframe = validate_indicator_timeframe(timeframe)
        return self.sector_features_base + self.sector_features_pattern.format(
            timeframe=timeframe.value,
            sector_level=self._normalize_sector_level(sector_level),
            sector_code=self._normalize_path_part(sector_code, "sector_code"),
        )

    def sector_rotation_backtest(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        sector_level: int,
    ) -> str:
        """Build S3 path for sector rotation backtest output data."""
        return self._strategy_sector_level_path(
            base=self.sector_rotation_backtests_base,
            pattern=self.sector_rotation_backtests_pattern,
            strategy=strategy,
            timeframe=timeframe,
            sector_level=sector_level,
        )

    def sector_transition_predictions(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        sector_level: int,
    ) -> str:
        """Build S3 path for Sector Transition prediction rows."""
        return self._strategy_sector_level_path(
            base=self.sector_transition_predictions_base,
            pattern=self.sector_transition_predictions_pattern,
            strategy=strategy,
            timeframe=timeframe,
            sector_level=sector_level,
        )

    def sector_transition_decisions(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        sector_level: int,
    ) -> str:
        """Build S3 path for private Sector Transition decisions."""
        return self._strategy_sector_level_path(
            base=self.sector_transition_decisions_base,
            pattern=self.sector_transition_decisions_pattern,
            strategy=strategy,
            timeframe=timeframe,
            sector_level=sector_level,
        )

    def sector_transition_probabilities(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        sector_level: int,
    ) -> str:
        """Build S3 path for Sector Transition probability matrices."""
        return self._strategy_sector_level_path(
            base=self.sector_transition_probabilities_base,
            pattern=self.sector_transition_probabilities_pattern,
            strategy=strategy,
            timeframe=timeframe,
            sector_level=sector_level,
        )

    def sector_transition_outcomes(
        self,
        strategy: str,
        timeframe: Timeframe | str,
        sector_level: int,
    ) -> str:
        """Build S3 path for evaluated Sector Transition outcomes."""
        return self._strategy_sector_level_path(
            base=self.sector_transition_outcomes_base,
            pattern=self.sector_transition_outcomes_pattern,
            strategy=strategy,
            timeframe=timeframe,
            sector_level=sector_level,
        )

    def _strategy_sector_level_path(
        self,
        *,
        base: str,
        pattern: str,
        strategy: str,
        timeframe: Timeframe | str,
        sector_level: int,
    ) -> str:
        timeframe = validate_indicator_timeframe(timeframe)
        return base + pattern.format(
            strategy=self._normalize_path_part(strategy, "strategy"),
            timeframe=timeframe.value,
            sector_level=self._normalize_sector_level(sector_level),
        )

    @staticmethod
    def _normalize_sector_level(value: int | str) -> int:
        try:
            level = int(value)
        except (TypeError, ValueError) as exc:
            raise ValueError("sector_level must be an integer") from exc
        if level < 1:
            raise ValueError("sector_level must be greater than or equal to 1")
        return level

    @classmethod
    def from_config(cls, config: dict) -> StockDataPaths:
        """Create StockDataPaths from configuration dictionary.

        Expected config structure:
        {
            "paths": {
                "symbols": {"base": "symbols/", "pattern": "{exchange}.parquet"},
                "eod": {"base": "eod/", "pattern": "{exchange}/{code}.parquet"},
                "indicators": {
                    "base": "indicators/",
                    "pattern": "{source}/{timeframe}/{exchange}/{code}.parquet",
                },
            }
        }

        Args:
            config: Configuration dictionary from s3-paths.yaml

        Returns:
            Configured StockDataPaths instance

        Examples:
            >>> config = {
            ...     "paths": {
            ...         "symbols": {
                "base": "symbols/", "pattern": "{exchange}.parquet"
            },
            ...         "eod": {
                "base": "eod/", "pattern": "{exchange}/{code}.parquet"
            },
            ...         "indicators": {
                "base": "indicators/",
                "pattern": "{source}/{timeframe}/{exchange}/{code}.parquet",
            },
            ...     }
            ... }
            >>> paths = StockDataPaths.from_config(config)
            >>> paths.symbols("HOSE")
            'symbols/hose.parquet'
        """
        paths_config = config.get("paths", {})

        symbols_cfg = paths_config.get("symbols", {})
        eod_cfg = paths_config.get("eod", {})
        indicators_cfg = paths_config.get("indicators", {})
        signals_cfg = paths_config.get("signals", {})
        signal_current_cfg = paths_config.get("signal-current", {})
        symbol_features_cfg = paths_config.get("symbol-features", {})
        sector_features_cfg = paths_config.get("sector-features", {})
        sector_rotation_backtests_cfg = paths_config.get(
            "sector-rotation-backtests", {}
        )
        sector_transition_predictions_cfg = paths_config.get(
            "sector-transition-predictions", {}
        )
        sector_transition_decisions_cfg = paths_config.get(
            "sector-transition-decisions", {}
        )
        sector_transition_probabilities_cfg = paths_config.get(
            "sector-transition-probabilities", {}
        )
        sector_transition_outcomes_cfg = paths_config.get(
            "sector-transition-outcomes", {}
        )

        return cls(
            symbols_base=symbols_cfg.get("base", "symbols/"),
            symbols_pattern=symbols_cfg.get("pattern", "{exchange}.parquet"),
            eod_base=eod_cfg.get("base", "eod/"),
            eod_pattern=eod_cfg.get("pattern", "{exchange}/{code}.parquet"),
            indicators_base=indicators_cfg.get("base", "indicators/"),
            indicators_pattern=indicators_cfg.get(
                "pattern", "{source}/{timeframe}/{exchange}/{code}.parquet"
            ),
            signals_base=signals_cfg.get("base", "signals/"),
            signals_pattern=signals_cfg.get(
                "pattern", "{strategy}/{timeframe}/{exchange}.parquet"
            ),
            signal_current_base=signal_current_cfg.get("base", "signals/"),
            signal_current_pattern=signal_current_cfg.get(
                "pattern", "{strategy}/{timeframe}/{exchange}.parquet"
            ),
            symbol_features_base=symbol_features_cfg.get("base", "features/symbol/"),
            symbol_features_pattern=symbol_features_cfg.get(
                "pattern", "{timeframe}/{exchange}/{code}.parquet"
            ),
            sector_features_base=sector_features_cfg.get("base", "features/sector/"),
            sector_features_pattern=sector_features_cfg.get(
                "pattern", "{timeframe}/lv{sector_level}/{sector_code}.parquet"
            ),
            sector_rotation_backtests_base=sector_rotation_backtests_cfg.get(
                "base", "backtests/sector-rotation/"
            ),
            sector_rotation_backtests_pattern=sector_rotation_backtests_cfg.get(
                "pattern", "{strategy}/{timeframe}/lv{sector_level}.parquet"
            ),
            sector_transition_predictions_base=sector_transition_predictions_cfg.get(
                "base", "research/sector-transition/predictions/"
            ),
            sector_transition_predictions_pattern=sector_transition_predictions_cfg.get(
                "pattern", "{strategy}/{timeframe}/lv{sector_level}.parquet"
            ),
            sector_transition_decisions_base=sector_transition_decisions_cfg.get(
                "base", "research/sector-transition/decisions/"
            ),
            sector_transition_decisions_pattern=sector_transition_decisions_cfg.get(
                "pattern", "{strategy}/{timeframe}/lv{sector_level}.parquet"
            ),
            sector_transition_probabilities_base=sector_transition_probabilities_cfg.get(
                "base", "research/sector-transition/probabilities/"
            ),
            sector_transition_probabilities_pattern=sector_transition_probabilities_cfg.get(
                "pattern", "{strategy}/{timeframe}/lv{sector_level}.parquet"
            ),
            sector_transition_outcomes_base=sector_transition_outcomes_cfg.get(
                "base", "research/sector-transition/outcomes/"
            ),
            sector_transition_outcomes_pattern=sector_transition_outcomes_cfg.get(
                "pattern", "{strategy}/{timeframe}/lv{sector_level}.parquet"
            ),
        )
