-- Screener presets and price alerts per user
-- Depends on: V1 (users), V3 (stocks)

-- Screener filter presets

CREATE TABLE screener_presets (
    id           SERIAL PRIMARY KEY,
    user_id      INTEGER     REFERENCES users(id) ON DELETE CASCADE,  -- NULL = built-in system preset
    name         TEXT        NOT NULL,
    description  TEXT,
    filters_json JSONB       NOT NULL,
    -- Example: {"pe_lt": 15, "roe_gt": 15, "exchange": "HOSE",
    --            "market_cap_gt": 1000, "revenue_growth_yoy_gt": 10}
    sort_by      TEXT,       -- field name from financial_ratios
    sort_dir     TEXT        DEFAULT 'desc',
    is_public    BOOLEAN     DEFAULT FALSE,
    use_count    INTEGER     DEFAULT 0,
    created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_screener_user   ON screener_presets(user_id);
CREATE INDEX idx_screener_public ON screener_presets(is_public);


-- Price alerts

CREATE TABLE price_alerts (
    id              SERIAL PRIMARY KEY,
    user_id         INTEGER     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    symbol          TEXT        NOT NULL REFERENCES stocks(ticker),
    condition       TEXT        NOT NULL,
    -- above | below | cross_up | cross_down | pct_change_up | pct_change_down
    target_price    NUMERIC,
    target_pct      NUMERIC,                -- used when condition is pct_change_*
    note            TEXT,
    is_active       BOOLEAN     DEFAULT TRUE,
    is_triggered    BOOLEAN     DEFAULT FALSE,
    triggered_at    TIMESTAMP,
    triggered_price NUMERIC,
    notify_email    BOOLEAN     DEFAULT TRUE,
    notify_push     BOOLEAN     DEFAULT FALSE,
    created_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_alerts_user   ON price_alerts(user_id);
CREATE INDEX idx_alerts_symbol ON price_alerts(symbol);
CREATE INDEX idx_alerts_active ON price_alerts(is_active) WHERE is_active = TRUE;
