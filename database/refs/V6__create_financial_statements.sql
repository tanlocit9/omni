-- Balance sheet, income statement, cash flow statement, and raw JSON report store
-- Depends on: V3 (stocks)

-- Balance sheet

CREATE TABLE balance_sheet (
    id                              SERIAL PRIMARY KEY,
    symbol                          TEXT        NOT NULL,
    period                          TEXT        NOT NULL,  -- annual | quarterly
    year                            INTEGER     NOT NULL,
    quarter                         INTEGER,
    asset_current                   NUMERIC,
    cash_and_equivalents            NUMERIC,
    short_term_investments          NUMERIC,
    accounts_receivable             NUMERIC,
    inventory                       NUMERIC,
    current_assets_other            NUMERIC,
    asset_non_current               NUMERIC,
    long_term_receivables           NUMERIC,
    fixed_assets                    NUMERIC,
    long_term_investments           NUMERIC,
    non_current_assets_other        NUMERIC,
    total_assets                    NUMERIC,
    liabilities_total               NUMERIC,
    liabilities_current             NUMERIC,
    liabilities_non_current         NUMERIC,
    equity_total                    NUMERIC,
    share_capital                   NUMERIC,
    retained_earnings               NUMERIC,
    equity_other                    NUMERIC,
    total_equity_and_liabilities    NUMERIC,
    short_term_debt                 NUMERIC,
    long_term_debt                  NUMERIC,
    total_debt                      NUMERIC,    -- short_term_debt + long_term_debt
    net_debt                        NUMERIC,    -- total_debt - cash_and_equivalents
    goodwill                        NUMERIC,
    intangible_assets               NUMERIC,
    minority_interest_bs            NUMERIC,
    data_json                       TEXT,
    source                          TEXT        DEFAULT 'VCI',
    audited                         BOOLEAN     DEFAULT FALSE,
    created_at                      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, period, year, quarter),
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_balance_sheet_symbol ON balance_sheet(symbol);
CREATE INDEX idx_balance_sheet_year   ON balance_sheet(year);
CREATE INDEX idx_balance_sheet_period ON balance_sheet(period);


-- Income statement

CREATE TABLE income_statement (
    id                              SERIAL PRIMARY KEY,
    symbol                          TEXT        NOT NULL,
    period                          TEXT        NOT NULL,
    year                            INTEGER     NOT NULL,
    quarter                         INTEGER,
    revenue                         NUMERIC,
    revenue_growth                  NUMERIC,
    net_profit_parent_company       NUMERIC,
    profit_growth                   NUMERIC,
    net_revenue                     NUMERIC,
    cost_of_goods_sold              NUMERIC,
    gross_profit                    NUMERIC,
    financial_income                NUMERIC,
    financial_expense               NUMERIC,
    net_financial_income            NUMERIC,
    operating_expenses              NUMERIC,
    operating_profit                NUMERIC,
    other_income                    NUMERIC,
    profit_before_tax               NUMERIC,
    corporate_income_tax            NUMERIC,
    deferred_income_tax             NUMERIC,
    net_profit                      NUMERIC,
    minority_interest               NUMERIC,
    net_profit_parent_company_post  NUMERIC,
    eps                             NUMERIC,
    ebitda                          NUMERIC,
    ebit                            NUMERIC,
    interest_expense                NUMERIC,
    depreciation_amortization       NUMERIC,
    shares_diluted                  BIGINT,
    eps_diluted                     NUMERIC,
    data_json                       TEXT,
    source                          TEXT        DEFAULT 'VCI',
    audited                         BOOLEAN     DEFAULT FALSE,
    created_at                      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, period, year, quarter),
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_income_statement_symbol ON income_statement(symbol);
CREATE INDEX idx_income_statement_year   ON income_statement(year);
CREATE INDEX idx_income_statement_period ON income_statement(period);


-- Cash flow statement

CREATE TABLE cash_flow_statement (
    id                                                              SERIAL PRIMARY KEY,
    symbol                                                          TEXT        NOT NULL,
    period                                                          TEXT        NOT NULL,
    year                                                            INTEGER     NOT NULL,
    quarter                                                         INTEGER,
    profit_before_tax                                               NUMERIC,
    depreciation_fixed_assets                                       NUMERIC,
    provision_credit_loss_real_estate                               NUMERIC,
    profit_loss_from_disposal_fixed_assets                          NUMERIC,
    profit_loss_investment_activities                               NUMERIC,
    interest_income                                                 NUMERIC,
    interest_and_dividend_income                                    NUMERIC,
    net_cash_flow_from_operating_activities_before_working_capital  NUMERIC,
    increase_decrease_receivables                                   NUMERIC,
    increase_decrease_inventory                                     NUMERIC,
    increase_decrease_payables                                      NUMERIC,
    increase_decrease_prepaid_expenses                              NUMERIC,
    interest_expense_paid                                           NUMERIC,
    corporate_income_tax_paid                                       NUMERIC,
    other_cash_from_operating_activities                            NUMERIC,
    other_cash_paid_for_operating_activities                        NUMERIC,
    net_cash_from_operating_activities                              NUMERIC,
    purchase_purchase_fixed_assets                                  NUMERIC,
    proceeds_from_disposal_fixed_assets                             NUMERIC,
    loans_other_collections                                         NUMERIC,
    investments_other_companies                                     NUMERIC,
    proceeds_from_sale_investments_other_companies                  NUMERIC,
    dividends_and_profits_received                                  NUMERIC,
    net_cash_from_investing_activities                              NUMERIC,
    increase_share_capital_contribution_equity                      NUMERIC,
    payment_for_capital_contribution_buyback_shares                 NUMERIC,
    proceeds_from_borrowings                                        NUMERIC,
    repayments_of_borrowings                                        NUMERIC,
    lease_principal_payments                                        NUMERIC,
    dividends_paid                                                  NUMERIC,
    other_cash_from_financing_activities                            NUMERIC,
    net_cash_from_financing_activities                              NUMERIC,
    net_cash_flow_period                                            NUMERIC,
    cash_and_cash_equivalents_beginning                             NUMERIC,
    cash_and_cash_equivalents_ending                                NUMERIC,
    free_cash_flow                                                  NUMERIC GENERATED ALWAYS AS
        (net_cash_from_operating_activities + purchase_purchase_fixed_assets) STORED,
    data_json                                                       TEXT,
    source                                                          TEXT        DEFAULT 'VCI',
    audited                                                         BOOLEAN     DEFAULT FALSE,
    created_at                                                      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at                                                      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, period, year, quarter),
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_cash_flow_symbol ON cash_flow_statement(symbol);
CREATE INDEX idx_cash_flow_year   ON cash_flow_statement(year);
CREATE INDEX idx_cash_flow_period ON cash_flow_statement(period);


-- Raw JSON report store (fallback / source of truth from data provider)

CREATE TABLE financial_reports (
    id          SERIAL PRIMARY KEY,
    symbol      TEXT        NOT NULL,
    report_type TEXT        NOT NULL,  -- BALANCE_SHEET | INCOME | CASH_FLOW | RATIO
    period      TEXT        NOT NULL,
    year        INTEGER     NOT NULL,
    quarter     INTEGER,
    data_json   TEXT        NOT NULL,
    source      TEXT        DEFAULT 'VCI',
    created_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, report_type, period, year, quarter),
    FOREIGN KEY (symbol) REFERENCES stocks(ticker)
);

CREATE INDEX idx_financial_reports_symbol ON financial_reports(symbol);
CREATE INDEX idx_financial_reports_type   ON financial_reports(report_type);
CREATE INDEX idx_financial_reports_year   ON financial_reports(year);
CREATE INDEX idx_financial_reports_period ON financial_reports(period);
