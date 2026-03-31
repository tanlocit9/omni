-- Pre-computed financial ratios for fundamental analysis (FA) and screener
-- Depends on: V3 (stocks)

CREATE TABLE financial_ratios (
    id                          SERIAL PRIMARY KEY,
    symbol                      TEXT        NOT NULL,
    period                      TEXT        NOT NULL,
    year                        INTEGER     NOT NULL,
    quarter                     INTEGER,

    -- Valuation
    price_to_book               NUMERIC,
    price_to_earnings           NUMERIC,
    price_to_sales              NUMERIC,
    price_to_cash_flow          NUMERIC,
    ev_to_ebitda                NUMERIC,
    ev_to_ebit                  NUMERIC,
    ev_to_sales                 NUMERIC,
    peg_ratio                   NUMERIC,    -- P/E divided by earnings growth rate
    graham_number               NUMERIC,    -- sqrt(22.5 x EPS x BVPS)

    -- Market cap & shares
    market_cap_billions         NUMERIC,
    enterprise_value_billions   NUMERIC,
    shares_outstanding_millions NUMERIC,

    -- Per-share metrics
    eps_vnd                     NUMERIC,
    eps_diluted_vnd             NUMERIC,
    bvps_vnd                    NUMERIC,

    -- Profitability
    roe                         NUMERIC,
    roa                         NUMERIC,
    roic                        NUMERIC,
    roce                        NUMERIC,    -- return on capital employed
    gross_margin                NUMERIC,
    ebit_margin                 NUMERIC,
    ebitda_margin               NUMERIC,
    net_profit_margin           NUMERIC,
    fcf_margin                  NUMERIC,    -- free cash flow margin

    -- Operating efficiency
    asset_turnover              NUMERIC,
    fixed_asset_turnover        NUMERIC,
    inventory_turnover          NUMERIC,
    days_sales_outstanding      NUMERIC,
    days_inventory_outstanding  NUMERIC,
    days_payable_outstanding    NUMERIC,
    cash_conversion_cycle       NUMERIC,

    -- Leverage & liquidity
    debt_to_equity              NUMERIC,
    debt_to_equity_adjusted     NUMERIC,
    net_debt_to_ebitda          NUMERIC,
    interest_coverage_ratio     NUMERIC,
    financial_leverage          NUMERIC,
    fixed_assets_to_equity      NUMERIC,
    equity_to_charter_capital   NUMERIC,
    current_ratio               NUMERIC,
    quick_ratio                 NUMERIC,
    cash_ratio                  NUMERIC,

    -- Year-over-year growth
    revenue_growth_yoy          NUMERIC,
    net_profit_growth_yoy       NUMERIC,
    eps_growth_yoy              NUMERIC,
    book_value_growth_yoy       NUMERIC,

    -- Absolute figures (billions VND)
    ebitda_billions             NUMERIC,
    ebit_billions               NUMERIC,
    fcf_billions                NUMERIC,
    net_debt_billions           NUMERIC,

    -- Dividends
    dividend_payout_ratio       NUMERIC,
    dividend_yield              NUMERIC,
    dividend_per_share          NUMERIC,

    -- Risk
    beta                        NUMERIC,
    altman_z_score              NUMERIC,    -- bankruptcy prediction score

    data_json   TEXT,
    source      TEXT        DEFAULT 'VCI',
    created_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, period, year, quarter),
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_ratios_symbol ON financial_ratios(symbol);
CREATE INDEX idx_ratios_year   ON financial_ratios(year);
CREATE INDEX idx_ratios_period ON financial_ratios(period);
