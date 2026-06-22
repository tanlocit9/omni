-- Reference / lookup tables: exchanges, indices, industries

CREATE TABLE exchanges (
    exchange        TEXT PRIMARY KEY,
    exchange_name   TEXT,
    exchange_code   TEXT,
    country         TEXT    DEFAULT 'VN',
    currency        TEXT    DEFAULT 'VND',
    timezone        TEXT    DEFAULT 'Asia/Ho_Chi_Minh',
    open_time       TIME,
    close_time      TIME,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE indices (
    index_code      TEXT PRIMARY KEY,
    index_name      TEXT,
    description     TEXT,
    group_name      TEXT,
    index_id        INTEGER,
    sector_id       NUMERIC,
    base_value      NUMERIC,
    base_date       DATE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE industries (
    icb_code        TEXT PRIMARY KEY,
    icb_name        TEXT,
    en_icb_name     TEXT,
    level           INTEGER,
    parent_code     TEXT    REFERENCES industries(icb_code),  -- self-referencing ICB hierarchy
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_industries_parent ON industries(parent_code);
