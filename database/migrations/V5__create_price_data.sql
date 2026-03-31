-- Stock daily OHLCV, intraday tick data, and index daily OHLCV
-- Depends on: V2 (indices), V3 (stocks)

-- Daily OHLCV for individual stocks

CREATE TABLE stock_price_history (
    id               SERIAL PRIMARY KEY,
    symbol           TEXT        NOT NULL,
    time             DATE        NOT NULL,
    open             NUMERIC,
    high             NUMERIC,
    low              NUMERIC,
    close            NUMERIC,
    volume           BIGINT,
    value            BIGINT,             -- total trading value (VND)
    adjusted_close   NUMERIC,            -- split/dividend-adjusted close price (used for TA)
    adjusted_ratio   NUMERIC DEFAULT 1,  -- adjustment factor
    foreign_buy_vol  BIGINT,             -- foreign investor buy volume
    foreign_sell_vol BIGINT,             -- foreign investor sell volume
    foreign_buy_val  BIGINT,             -- foreign investor buy value (VND)
    foreign_sell_val BIGINT,             -- foreign investor sell value (VND)
    put_through_vol  BIGINT,             -- negotiated/put-through deal volume
    put_through_val  BIGINT,             -- negotiated/put-through deal value (VND)
    market_cap       BIGINT,             -- market capitalisation at session close (VND)
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, time)
);

CREATE INDEX idx_price_history_symbol       ON stock_price_history(symbol);
CREATE INDEX idx_price_history_time         ON stock_price_history(time);
CREATE INDEX idx_price_history_symbol_time  ON stock_price_history(symbol, time);


-- Intraday tick / matched-order data

CREATE TABLE stock_intraday (
    symbol          TEXT,
    time            TIMESTAMP,
    price           NUMERIC,
    accumulated_val BIGINT,
    accumulated_vol BIGINT,
    volume          INTEGER,
    match_type      TEXT,   -- LO | ATO | ATC | PUT_THROUGH
    PRIMARY KEY (symbol, time)
);

CREATE INDEX idx_intraday_symbol_time ON stock_intraday(symbol, time);


-- Daily OHLCV for market indices (VNINDEX, VN30, HNX, ...)

CREATE TABLE index_price_history (
    id          SERIAL PRIMARY KEY,
    index_code  TEXT        NOT NULL,
    time        DATE        NOT NULL,
    open        NUMERIC,
    high        NUMERIC,
    low         NUMERIC,
    close       NUMERIC,
    volume      BIGINT,
    value       BIGINT,
    advances    INTEGER,    -- number of advancing stocks
    declines    INTEGER,    -- number of declining stocks
    no_changes  INTEGER,    -- number of unchanged stocks
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (index_code, time),
    FOREIGN KEY (index_code) REFERENCES indices(index_code)
);

CREATE INDEX idx_index_price_code ON index_price_history(index_code);
CREATE INDEX idx_index_price_time ON index_price_history(time);
