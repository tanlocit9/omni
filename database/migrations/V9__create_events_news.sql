-- Corporate events (dividends, rights issues, AGM, etc.) and news articles
-- Depends on: V3 (stocks)

CREATE TABLE events (
    id                  TEXT PRIMARY KEY,
    symbol              TEXT,
    event_title         TEXT,
    en_event_title      TEXT,
    public_date         DATE,
    issue_date          DATE,
    source_url          TEXT,
    event_list_code     TEXT,
    -- DIVIDEND | BONUS_SHARE | RIGHTS_ISSUE | AGM | EGM
    -- STOCK_SPLIT | MERGER | LISTING | DELISTING
    ratio               NUMERIC,
    value               NUMERIC,
    record_date         DATE,
    exright_date        DATE,
    payment_date        DATE,       -- dividend payment date
    event_list_name     TEXT,
    en_event_list_name  TEXT,
    created_at          TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_events_symbol      ON events(symbol);
CREATE INDEX idx_events_public_date ON events(public_date);
CREATE INDEX idx_events_exright     ON events(exright_date);


CREATE TABLE news (
    id                  TEXT PRIMARY KEY,
    symbol              TEXT,
    news_title          TEXT,
    news_sub_title      TEXT,
    friendly_sub_title  TEXT,
    news_image_url      TEXT,
    news_source_link    TEXT,
    public_date         TIMESTAMP,
    news_id             TEXT,
    news_short_content  TEXT,
    news_full_content   TEXT,
    close_price         NUMERIC,
    ref_price           NUMERIC,
    floor               NUMERIC,
    ceiling             NUMERIC,
    price_change_pct    NUMERIC,
    sentiment           TEXT,       -- positive | negative | neutral (NLP-derived)
    created_at          TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_news_symbol      ON news(symbol);
CREATE INDEX idx_news_public_date ON news(public_date);
