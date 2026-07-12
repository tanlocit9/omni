"""S3 path construction for stock data."""

from dataclasses import dataclass

from py_common.config.constants import Timeframe


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
        ...     indicators_pattern="{timeframe}/{exchange}/{code}.parquet"
        ... )
        >>> paths.symbols("HOSE")
        'symbols/hose.parquet'
        >>> paths.eod("HOSE", "HPG")
        'eod/hose/hpg.parquet'
        >>> paths.indicators(Timeframe.ONE_DAY, "HOSE", "HPG")
        'indicators/1d/hose/hpg.parquet'
    """

    symbols_base: str
    symbols_pattern: str
    eod_base: str
    eod_pattern: str
    indicators_base: str
    indicators_pattern: str

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
            exchange=exchange.lower()
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
            exchange=exchange.lower(), code=code.lower()
        )

    def indicators(
        self, timeframe: Timeframe | str, exchange: str, code: str
    ) -> str:
        """Build S3 path for indicator data file.
        
        Validates timeframe against allowed values and normalizes exchange/code
        to lowercase.
        
        Args:
            timeframe: Timeframe interval (Timeframe enum or string)
            exchange: Exchange name (will be normalized to lowercase)
            code: Stock ticker code (will be normalized to lowercase)
            
        Returns:
            Path like: indicators/1d/hose/hpg.parquet
            
        Raises:
            ValueError: If timeframe is not a valid Timeframe value
            
        Examples:
            >>> paths.indicators(Timeframe.ONE_DAY, "HOSE", "HPG")
            'indicators/1d/hose/hpg.parquet'
            >>> paths.indicators("1h", "HNX", "SHS")
            'indicators/1h/hnx/shs.parquet'
            >>> paths.indicators("invalid", "HOSE", "HPG")
            Traceback (most recent call last):
                ...
            ValueError: Invalid timeframe 'invalid'.
            Must be one of: 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M
        """
        # Validate timeframe
        if isinstance(timeframe, str):
            timeframe = Timeframe.validate(timeframe)

        return self.indicators_base + self.indicators_pattern.format(
            timeframe=timeframe.value,
            exchange=exchange.lower(),
            code=code.lower(),
        )

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
                    "pattern": "{timeframe}/{exchange}/{code}.parquet",
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
                "pattern": "{timeframe}/{exchange}/{code}.parquet",
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

        return cls(
            symbols_base=symbols_cfg.get("base", "symbols/"),
            symbols_pattern=symbols_cfg.get("pattern", "{exchange}.parquet"),
            eod_base=eod_cfg.get("base", "eod/"),
            eod_pattern=eod_cfg.get("pattern", "{exchange}/{code}.parquet"),
            indicators_base=indicators_cfg.get("base", "indicators/"),
            indicators_pattern=indicators_cfg.get(
                "pattern", "{timeframe}/{exchange}/{code}.parquet"
            ),
        )