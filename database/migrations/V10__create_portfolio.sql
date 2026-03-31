-- Watchlists, portfolios, and transaction history per user
-- Depends on: V1 (users), V3 (stocks)

-- Watchlists

CREATE TABLE watchlists (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    is_default  BOOLEAN     DEFAULT FALSE,
    color       TEXT,       -- hex colour used in the UI (e.g. #FF5733)
    description TEXT,
    created_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_watchlists_user ON watchlists(user_id);


CREATE TABLE watchlist_items (
    id              SERIAL PRIMARY KEY,
    watchlist_id    INTEGER     NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
    symbol          TEXT        NOT NULL REFERENCES stocks(ticker),
    note            TEXT,
    target_price    NUMERIC,    -- user's personal target price
    added_at        TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (watchlist_id, symbol)
);

CREATE INDEX idx_watchlist_items_watchlist ON watchlist_items(watchlist_id);
CREATE INDEX idx_watchlist_items_symbol    ON watchlist_items(symbol);


-- Portfolios

CREATE TABLE portfolios (
    id               SERIAL PRIMARY KEY,
    user_id          INTEGER     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name             TEXT        NOT NULL,
    description      TEXT,
    currency         TEXT        DEFAULT 'VND',
    initial_cash     NUMERIC     DEFAULT 0,
    is_paper_trading BOOLEAN     DEFAULT FALSE,  -- virtual / backtesting portfolio
    broker           TEXT,                       -- VCSC | SSI | MBS | VPS | TCBS
    created_at       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_portfolios_user ON portfolios(user_id);


CREATE TABLE portfolio_transactions (
    id           SERIAL PRIMARY KEY,
    portfolio_id INTEGER     NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    symbol       TEXT        NOT NULL REFERENCES stocks(ticker),
    type         TEXT        NOT NULL,
    -- BUY | SELL | DIVIDEND | STOCK_DIVIDEND | RIGHTS
    -- TRANSFER_IN | TRANSFER_OUT | CASH_IN | CASH_OUT
    quantity     BIGINT      NOT NULL,
    price        NUMERIC     NOT NULL,
    fee          NUMERIC     DEFAULT 0,
    tax          NUMERIC     DEFAULT 0,
    total_amount NUMERIC GENERATED ALWAYS AS (quantity * price + fee + tax) STORED,
    txn_date     DATE        NOT NULL,
    note         TEXT,
    created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_portfolio_txn_portfolio ON portfolio_transactions(portfolio_id);
CREATE INDEX idx_portfolio_txn_symbol    ON portfolio_transactions(symbol);
CREATE INDEX idx_portfolio_txn_date      ON portfolio_transactions(txn_date);
