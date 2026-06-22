-- Cached technical indicators (MA, RSI, MACD, Bollinger Bands, etc.)
-- Depends on: V3 (stocks)

CREATE TABLE technical_indicators (
    symbol        TEXT    NOT NULL,
    time          DATE    NOT NULL,
    indicator     TEXT    NOT NULL,
    -- Supported values:
    -- Trend:     SMA | EMA | WMA | DEMA | TEMA
    -- Momentum:  RSI | MACD | MACD_SIGNAL | MACD_HIST | STOCH_K | STOCH_D | CCI | WILLIAMS_R | MFI
    -- Volatility: BB_UPPER | BB_MIDDLE | BB_LOWER | ATR | STDDEV
    -- Volume:    OBV | VWAP | CMF
    -- Other:     ADX | AROON_UP | AROON_DOWN | PSAR
    period        INTEGER NOT NULL,  -- e.g. 14, 20, 50, 200
    value         NUMERIC,
    value2        NUMERIC,           -- secondary output: MACD signal line, BB middle band
    value3        NUMERIC,           -- tertiary output: MACD histogram
    timeframe     TEXT    NOT NULL DEFAULT 'daily',  -- daily | weekly | monthly
    calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (symbol, time, indicator, period, timeframe)
);

CREATE INDEX idx_ta_symbol_time ON technical_indicators(symbol, time);
CREATE INDEX idx_ta_indicator   ON technical_indicators(indicator, period);
