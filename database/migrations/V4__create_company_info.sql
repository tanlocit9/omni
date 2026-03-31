-- Company overview, management officers, shareholders, and subsidiaries
-- Depends on: V3 (stocks)

CREATE TABLE company_overview (
    symbol                      TEXT PRIMARY KEY,
    id                          TEXT,
    issue_share                 BIGINT,
    history                     TEXT,
    company_profile             TEXT,
    icb_name3                   TEXT,
    icb_name2                   TEXT,
    icb_name4                   TEXT,
    financial_ratio_issue_share BIGINT,
    charter_capital             BIGINT,

    website                     TEXT,
    phone                       TEXT,
    email                       TEXT,
    address                     TEXT,
    province                    TEXT,
    founding_year               INTEGER,
    employee_count              INTEGER,
    fiscal_year_end             TEXT    DEFAULT '12/31',  -- month/day the fiscal year ends

    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_company_overview_symbol ON company_overview(symbol);


CREATE TABLE officers (
    id                  TEXT PRIMARY KEY,
    symbol              TEXT,
    officer_name        TEXT,
    officer_position    TEXT,
    position_short_name TEXT,
    update_date         DATE,
    officer_own_percent NUMERIC,
    quantity            BIGINT,
    status              TEXT DEFAULT 'working',  -- working | resigned
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_officers_symbol ON officers(symbol);
CREATE INDEX idx_officers_status ON officers(status);


CREATE TABLE shareholders (
    id                TEXT PRIMARY KEY,
    symbol            TEXT,
    share_holder      TEXT,
    quantity          BIGINT,
    share_own_percent NUMERIC,
    holder_type       TEXT,   -- individual | institution | foreign | state
    update_date       DATE,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_shareholders_symbol      ON shareholders(symbol);
CREATE INDEX idx_shareholders_update_date ON shareholders(update_date);


CREATE TABLE subsidiaries (
    id                TEXT PRIMARY KEY,
    symbol            TEXT,
    sub_organ_code    TEXT,
    ownership_percent NUMERIC,
    organ_name        TEXT,
    type              TEXT,   -- subsidiary | associate | joint_venture
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_subsidiaries_symbol ON subsidiaries(symbol);
