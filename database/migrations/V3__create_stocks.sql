-- Core stock / instrument master table and exchange-index-industry mapping tables
-- Depends on: V2 (exchanges, indices, industries)

CREATE TABLE stocks (
    ticker              TEXT PRIMARY KEY,
    organ_name          TEXT,
    en_organ_name       TEXT,
    organ_short_name    TEXT,
    en_organ_short_name TEXT,
    com_type_code       TEXT,       -- STOCK | BOND | ETF | FUND | COVERED_WARRANT
    status              TEXT        NOT NULL DEFAULT 'listed',  -- listed | suspended | delisted
    listed_date         DATE,
    delisted_date       DATE,
    company_id          TEXT,
    tax_code            TEXT,
    isin                TEXT,

    currency            TEXT        DEFAULT 'VND',
    par_value           NUMERIC     DEFAULT 10000,  -- face value (VND)
    lot_size            INTEGER     DEFAULT 100,    -- minimum trading lot
    shares_outstanding  BIGINT,                     -- shares currently in circulation
    shares_listed       BIGINT,                     -- total listed shares
    free_float_pct      NUMERIC,                    -- percentage of freely tradeable shares
    foreign_limit_pct   NUMERIC,                    -- maximum foreign ownership limit (49% or 30%)
    foreign_current_pct NUMERIC,                    -- current foreign ownership percentage

    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_stocks_status      ON stocks(status);
CREATE INDEX idx_stocks_ticker      ON stocks(ticker);
CREATE INDEX idx_stocks_com_type    ON stocks(com_type_code);


-- Stock ↔ Exchange mapping

CREATE TABLE stock_exchange (
    ticker      TEXT,
    exchange    TEXT,
    id          INTEGER,
    type        TEXT,
    PRIMARY KEY (ticker, exchange),
    FOREIGN KEY (ticker)   REFERENCES stocks(ticker),
    FOREIGN KEY (exchange) REFERENCES exchanges(exchange)
);

CREATE INDEX idx_stock_exchange_ticker   ON stock_exchange(ticker);
CREATE INDEX idx_stock_exchange_exchange ON stock_exchange(exchange);


-- Stock ↔ Index mapping

CREATE TABLE stock_index (
    ticker      TEXT,
    index_code  TEXT,
    PRIMARY KEY (ticker, index_code),
    FOREIGN KEY (ticker)     REFERENCES stocks(ticker),
    FOREIGN KEY (index_code) REFERENCES indices(index_code)
);

CREATE INDEX idx_stock_index_ticker ON stock_index(ticker);
CREATE INDEX idx_stock_index_index  ON stock_index(index_code);


-- Stock ↔ Industry mapping (ICB classification)

CREATE TABLE stock_industry (
    ticker          TEXT,
    icb_code        TEXT,
    icb_name2       TEXT,
    en_icb_name2    TEXT,
    icb_name3       TEXT,
    en_icb_name3    TEXT,
    icb_name4       TEXT,
    en_icb_name4    TEXT,
    icb_code1       TEXT,
    icb_code2       TEXT,
    icb_code3       TEXT,
    icb_code4       TEXT,
    PRIMARY KEY (ticker, icb_code),
    FOREIGN KEY (ticker)   REFERENCES stocks(ticker),
    FOREIGN KEY (icb_code) REFERENCES industries(icb_code)
);

CREATE INDEX idx_stock_industry_ticker ON stock_industry(ticker);
CREATE INDEX idx_stock_industry_icb    ON stock_industry(icb_code);
