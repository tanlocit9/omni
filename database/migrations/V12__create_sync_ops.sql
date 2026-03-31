-- Sync schedule configuration and operation run log
-- Depends on: (none — standalone operational tables)

-- Data source sync schedule

CREATE TABLE sync_config (
    id           SERIAL PRIMARY KEY,
    source       TEXT        NOT NULL,  -- VCI | vnstock | TCBS | SSI | FIREANT
    table_name   TEXT        NOT NULL,
    cron_expr    TEXT,                  -- e.g. '0 18 * * 1-5' (weekdays at 18:00)
    is_active    BOOLEAN     DEFAULT TRUE,
    last_run     TIMESTAMP,
    last_success TIMESTAMP,
    next_run     TIMESTAMP,
    config_json  JSONB,
    -- params, auth headers, pagination settings, date range, symbol filter, etc.
    created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source, table_name)
);


-- Sync run log

CREATE TABLE update_log (
    id               SERIAL PRIMARY KEY,
    table_name       TEXT,
    source           TEXT,               -- VCI | vnstock | TCBS | SSI | FIREANT
    records_inserted INTEGER DEFAULT 0,
    records_updated  INTEGER DEFAULT 0,
    records_skipped  INTEGER DEFAULT 0,
    total_records    INTEGER DEFAULT 0,
    duration_ms      INTEGER,            -- total execution time in milliseconds
    status           TEXT,               -- success | error | partial
    error_message    TEXT,
    sync_config_id   INTEGER REFERENCES sync_config(id),
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_update_log_table  ON update_log(table_name);
CREATE INDEX idx_update_log_time   ON update_log(update_time);
CREATE INDEX idx_update_log_status ON update_log(status);
