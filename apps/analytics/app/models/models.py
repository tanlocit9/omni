from typing import Optional
import datetime
import decimal

from sqlalchemy import BigInteger, Boolean, Column, Computed, Date, DateTime, Double, ForeignKeyConstraint, Index, Integer, Numeric, PrimaryKeyConstraint, String, Table, Text, Time, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import JSONB
from __future__ import annotations
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class Exchanges(Base):
    __tablename__ = 'exchanges'
    __table_args__ = (
        PrimaryKeyConstraint('exchange', name='exchanges_pkey'),
    )

    exchange: Mapped[str] = mapped_column(Text, primary_key=True)
    exchange_name: Mapped[Optional[str]] = mapped_column(Text)
    exchange_code: Mapped[Optional[str]] = mapped_column(Text)
    country: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VN'::text"))
    currency: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VND'::text"))
    timezone: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'Asia/Ho_Chi_Minh'::text"))
    open_time: Mapped[Optional[datetime.time]] = mapped_column(Time)
    close_time: Mapped[Optional[datetime.time]] = mapped_column(Time)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stock_exchange: Mapped[list['StockExchange']] = relationship('StockExchange', back_populates='exchanges')


class FlywaySchemaHistory(Base):
    __tablename__ = 'flyway_schema_history'
    __table_args__ = (
        PrimaryKeyConstraint('installed_rank', name='flyway_schema_history_pk'),
        Index('flyway_schema_history_s_idx', 'success')
    )

    installed_rank: Mapped[int] = mapped_column(Integer, primary_key=True)
    description: Mapped[str] = mapped_column(String(200), nullable=False)
    type: Mapped[str] = mapped_column(String(20), nullable=False)
    script: Mapped[str] = mapped_column(String(1000), nullable=False)
    installed_by: Mapped[str] = mapped_column(String(100), nullable=False)
    installed_on: Mapped[datetime.datetime] = mapped_column(DateTime, nullable=False, server_default=text('now()'))
    execution_time: Mapped[int] = mapped_column(Integer, nullable=False)
    success: Mapped[bool] = mapped_column(Boolean, nullable=False)
    version: Mapped[Optional[str]] = mapped_column(String(50))
    checksum: Mapped[Optional[int]] = mapped_column(Integer)


class Indices(Base):
    __tablename__ = 'indices'
    __table_args__ = (
        PrimaryKeyConstraint('index_code', name='indices_pkey'),
    )

    index_code: Mapped[str] = mapped_column(Text, primary_key=True)
    index_name: Mapped[Optional[str]] = mapped_column(Text)
    description: Mapped[Optional[str]] = mapped_column(Text)
    group_name: Mapped[Optional[str]] = mapped_column(Text)
    index_id: Mapped[Optional[int]] = mapped_column(Integer)
    sector_id: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    base_value: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    base_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped[list['Stocks']] = relationship('Stocks', secondary='stock_index', back_populates='indices')
    index_price_history: Mapped[list['IndexPriceHistory']] = relationship('IndexPriceHistory', back_populates='indices')


class Industries(Base):
    __tablename__ = 'industries'
    __table_args__ = (
        ForeignKeyConstraint(['parent_code'], ['industries.icb_code'], name='industries_parent_code_fkey'),
        PrimaryKeyConstraint('icb_code', name='industries_pkey'),
        Index('idx_industries_parent', 'parent_code')
    )

    icb_code: Mapped[str] = mapped_column(Text, primary_key=True)
    icb_name: Mapped[Optional[str]] = mapped_column(Text)
    en_icb_name: Mapped[Optional[str]] = mapped_column(Text)
    level: Mapped[Optional[int]] = mapped_column(Integer)
    parent_code: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    industries: Mapped[Optional['Industries']] = relationship('Industries', remote_side=[icb_code], back_populates='industries_reverse')
    industries_reverse: Mapped[list['Industries']] = relationship('Industries', remote_side=[parent_code], back_populates='industries')
    stock_industry: Mapped[list['StockIndustry']] = relationship('StockIndustry', back_populates='industries')


class StockIntraday(Base):
    __tablename__ = 'stock_intraday'
    __table_args__ = (
        PrimaryKeyConstraint('symbol', 'time', name='stock_intraday_pkey'),
        Index('idx_intraday_symbol_time', 'symbol', 'time')
    )

    symbol: Mapped[str] = mapped_column(Text, primary_key=True)
    time: Mapped[datetime.datetime] = mapped_column(DateTime, primary_key=True)
    price: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    accumulated_val: Mapped[Optional[int]] = mapped_column(BigInteger)
    accumulated_vol: Mapped[Optional[int]] = mapped_column(BigInteger)
    volume: Mapped[Optional[int]] = mapped_column(Integer)
    match_type: Mapped[Optional[str]] = mapped_column(Text)


class StockPriceHistory(Base):
    __tablename__ = 'stock_price_history'
    __table_args__ = (
        PrimaryKeyConstraint('id', name='stock_price_history_pkey'),
        UniqueConstraint('symbol', 'time', name='stock_price_history_symbol_time_key'),
        Index('idx_price_history_symbol', 'symbol'),
        Index('idx_price_history_symbol_time', 'symbol', 'time'),
        Index('idx_price_history_time', 'time')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    time: Mapped[datetime.date] = mapped_column(Date, nullable=False)
    open: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    high: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    low: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    close: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    volume: Mapped[Optional[int]] = mapped_column(BigInteger)
    value: Mapped[Optional[int]] = mapped_column(BigInteger)
    adjusted_close: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    adjusted_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, server_default=text('1'))
    foreign_buy_vol: Mapped[Optional[int]] = mapped_column(BigInteger)
    foreign_sell_vol: Mapped[Optional[int]] = mapped_column(BigInteger)
    foreign_buy_val: Mapped[Optional[int]] = mapped_column(BigInteger)
    foreign_sell_val: Mapped[Optional[int]] = mapped_column(BigInteger)
    put_through_vol: Mapped[Optional[int]] = mapped_column(BigInteger)
    put_through_val: Mapped[Optional[int]] = mapped_column(BigInteger)
    market_cap: Mapped[Optional[int]] = mapped_column(BigInteger)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))


class StockPrices(Base):
    __tablename__ = 'stock_prices'
    __table_args__ = (
        PrimaryKeyConstraint('id', name='stock_prices_pkey'),
        UniqueConstraint('code', 'date', name='uq_stock_price_code_date'),
        Index('ix_stock_prices_code', 'code')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    code: Mapped[str] = mapped_column(String(20), nullable=False)
    date: Mapped[datetime.date] = mapped_column(Date, nullable=False)
    open: Mapped[Optional[float]] = mapped_column(Double(53))
    high: Mapped[Optional[float]] = mapped_column(Double(53))
    low: Mapped[Optional[float]] = mapped_column(Double(53))
    close: Mapped[Optional[float]] = mapped_column(Double(53))
    volume: Mapped[Optional[float]] = mapped_column(Double(53))
    change: Mapped[Optional[float]] = mapped_column(Double(53))
    pct_change: Mapped[Optional[float]] = mapped_column(Double(53))


class Stocks(Base):
    __tablename__ = 'stocks'
    __table_args__ = (
        PrimaryKeyConstraint('ticker', name='stocks_pkey'),
        Index('idx_stocks_com_type', 'com_type_code'),
        Index('idx_stocks_status', 'status'),
        Index('idx_stocks_ticker', 'ticker')
    )

    ticker: Mapped[str] = mapped_column(Text, primary_key=True)
    status: Mapped[str] = mapped_column(Text, nullable=False, server_default=text("'listed'::text"))
    organ_name: Mapped[Optional[str]] = mapped_column(Text)
    en_organ_name: Mapped[Optional[str]] = mapped_column(Text)
    organ_short_name: Mapped[Optional[str]] = mapped_column(Text)
    en_organ_short_name: Mapped[Optional[str]] = mapped_column(Text)
    com_type_code: Mapped[Optional[str]] = mapped_column(Text)
    listed_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    delisted_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    company_id: Mapped[Optional[str]] = mapped_column(Text)
    tax_code: Mapped[Optional[str]] = mapped_column(Text)
    isin: Mapped[Optional[str]] = mapped_column(Text)
    currency: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VND'::text"))
    par_value: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, server_default=text('10000'))
    lot_size: Mapped[Optional[int]] = mapped_column(Integer, server_default=text('100'))
    shares_outstanding: Mapped[Optional[int]] = mapped_column(BigInteger)
    shares_listed: Mapped[Optional[int]] = mapped_column(BigInteger)
    free_float_pct: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    foreign_limit_pct: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    foreign_current_pct: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    indices: Mapped[list['Indices']] = relationship('Indices', secondary='stock_index', back_populates='stocks')
    balance_sheet: Mapped[list['BalanceSheet']] = relationship('BalanceSheet', back_populates='stocks')
    cash_flow_statement: Mapped[list['CashFlowStatement']] = relationship('CashFlowStatement', back_populates='stocks')
    events: Mapped[list['Events']] = relationship('Events', back_populates='stocks')
    financial_ratios: Mapped[list['FinancialRatios']] = relationship('FinancialRatios', back_populates='stocks')
    financial_reports: Mapped[list['FinancialReports']] = relationship('FinancialReports', back_populates='stocks')
    income_statement: Mapped[list['IncomeStatement']] = relationship('IncomeStatement', back_populates='stocks')
    news: Mapped[list['News']] = relationship('News', back_populates='stocks')
    officers: Mapped[list['Officers']] = relationship('Officers', back_populates='stocks')
    price_alerts: Mapped[list['PriceAlerts']] = relationship('PriceAlerts', back_populates='stocks')
    shareholders: Mapped[list['Shareholders']] = relationship('Shareholders', back_populates='stocks')
    stock_exchange: Mapped[list['StockExchange']] = relationship('StockExchange', back_populates='stocks')
    stock_industry: Mapped[list['StockIndustry']] = relationship('StockIndustry', back_populates='stocks')
    subsidiaries: Mapped[list['Subsidiaries']] = relationship('Subsidiaries', back_populates='stocks')
    portfolio_transactions: Mapped[list['PortfolioTransactions']] = relationship('PortfolioTransactions', back_populates='stocks')
    watchlist_items: Mapped[list['WatchlistItems']] = relationship('WatchlistItems', back_populates='stocks')


class SyncConfig(Base):
    __tablename__ = 'sync_config'
    __table_args__ = (
        PrimaryKeyConstraint('id', name='sync_config_pkey'),
        UniqueConstraint('source', 'table_name', name='sync_config_source_table_name_key')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    source: Mapped[str] = mapped_column(Text, nullable=False)
    table_name: Mapped[str] = mapped_column(Text, nullable=False)
    cron_expr: Mapped[Optional[str]] = mapped_column(Text)
    is_active: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('true'))
    last_run: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime)
    last_success: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime)
    next_run: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime)
    config_json: Mapped[Optional[dict]] = mapped_column(JSONB)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    update_log: Mapped[list['UpdateLog']] = relationship('UpdateLog', back_populates='sync_config')


class TechnicalIndicators(Base):
    __tablename__ = 'technical_indicators'
    __table_args__ = (
        PrimaryKeyConstraint('symbol', 'time', 'indicator', 'period', 'timeframe', name='technical_indicators_pkey'),
        Index('idx_ta_indicator', 'indicator', 'period'),
        Index('idx_ta_symbol_time', 'symbol', 'time')
    )

    symbol: Mapped[str] = mapped_column(Text, primary_key=True)
    time: Mapped[datetime.date] = mapped_column(Date, primary_key=True)
    indicator: Mapped[str] = mapped_column(Text, primary_key=True)
    period: Mapped[int] = mapped_column(Integer, primary_key=True)
    timeframe: Mapped[str] = mapped_column(Text, primary_key=True, server_default=text("'daily'::text"))
    value: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    value2: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    value3: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    calculated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))


class Users(Base):
    __tablename__ = 'users'
    __table_args__ = (
        PrimaryKeyConstraint('id', name='users_pkey'),
        UniqueConstraint('email', name='users_email_key'),
        Index('idx_users_email', 'email')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(Text, nullable=False)
    role: Mapped[str] = mapped_column(Text, nullable=False, server_default=text("'user'::text"))
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text('true'))
    timezone: Mapped[str] = mapped_column(Text, nullable=False, server_default=text("'Asia/Ho_Chi_Minh'::text"))
    created_at: Mapped[datetime.datetime] = mapped_column(DateTime, nullable=False, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[datetime.datetime] = mapped_column(DateTime, nullable=False, server_default=text('CURRENT_TIMESTAMP'))
    password_hash: Mapped[Optional[str]] = mapped_column(Text)
    display_name: Mapped[Optional[str]] = mapped_column(Text)
    settings_json: Mapped[Optional[dict]] = mapped_column(JSONB)
    last_login_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime)

    portfolios: Mapped[list['Portfolios']] = relationship('Portfolios', back_populates='user')
    price_alerts: Mapped[list['PriceAlerts']] = relationship('PriceAlerts', back_populates='user')
    screener_presets: Mapped[list['ScreenerPresets']] = relationship('ScreenerPresets', back_populates='user')
    watchlists: Mapped[list['Watchlists']] = relationship('Watchlists', back_populates='user')


class BalanceSheet(Base):
    __tablename__ = 'balance_sheet'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='balance_sheet_symbol_fkey'),
        PrimaryKeyConstraint('id', name='balance_sheet_pkey'),
        UniqueConstraint('symbol', 'period', 'year', 'quarter', name='balance_sheet_symbol_period_year_quarter_key'),
        Index('idx_balance_sheet_period', 'period'),
        Index('idx_balance_sheet_symbol', 'symbol'),
        Index('idx_balance_sheet_year', 'year')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    period: Mapped[str] = mapped_column(Text, nullable=False)
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    quarter: Mapped[Optional[int]] = mapped_column(Integer)
    asset_current: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    cash_and_equivalents: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    short_term_investments: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    accounts_receivable: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    inventory: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    current_assets_other: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    asset_non_current: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    long_term_receivables: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    fixed_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    long_term_investments: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    non_current_assets_other: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    total_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    liabilities_total: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    liabilities_current: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    liabilities_non_current: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    equity_total: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    share_capital: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    retained_earnings: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    equity_other: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    total_equity_and_liabilities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    short_term_debt: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    long_term_debt: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    total_debt: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_debt: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    goodwill: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    intangible_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    minority_interest_bs: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    data_json: Mapped[Optional[str]] = mapped_column(Text)
    source: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VCI'::text"))
    audited: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='balance_sheet')


class CashFlowStatement(Base):
    __tablename__ = 'cash_flow_statement'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='cash_flow_statement_symbol_fkey'),
        PrimaryKeyConstraint('id', name='cash_flow_statement_pkey'),
        UniqueConstraint('symbol', 'period', 'year', 'quarter', name='cash_flow_statement_symbol_period_year_quarter_key'),
        Index('idx_cash_flow_period', 'period'),
        Index('idx_cash_flow_symbol', 'symbol'),
        Index('idx_cash_flow_year', 'year')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    period: Mapped[str] = mapped_column(Text, nullable=False)
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    quarter: Mapped[Optional[int]] = mapped_column(Integer)
    profit_before_tax: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    depreciation_fixed_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    provision_credit_loss_real_estate: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    profit_loss_from_disposal_fixed_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    profit_loss_investment_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    interest_income: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    interest_and_dividend_income: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_cash_flow_from_operating_activities_before_working_capital: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    increase_decrease_receivables: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    increase_decrease_inventory: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    increase_decrease_payables: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    increase_decrease_prepaid_expenses: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    interest_expense_paid: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    corporate_income_tax_paid: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    other_cash_from_operating_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    other_cash_paid_for_operating_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_cash_from_operating_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    purchase_purchase_fixed_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    proceeds_from_disposal_fixed_assets: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    loans_other_collections: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    investments_other_companies: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    proceeds_from_sale_investments_other_companies: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    dividends_and_profits_received: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_cash_from_investing_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    increase_share_capital_contribution_equity: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    payment_for_capital_contribution_buyback_shares: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    proceeds_from_borrowings: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    repayments_of_borrowings: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    lease_principal_payments: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    dividends_paid: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    other_cash_from_financing_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_cash_from_financing_activities: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_cash_flow_period: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    cash_and_cash_equivalents_beginning: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    cash_and_cash_equivalents_ending: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    free_cash_flow: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, Computed('(net_cash_from_operating_activities + purchase_purchase_fixed_assets)', persisted=True))
    data_json: Mapped[Optional[str]] = mapped_column(Text)
    source: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VCI'::text"))
    audited: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='cash_flow_statement')


class CompanyOverview(Stocks):
    __tablename__ = 'company_overview'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='company_overview_symbol_fkey'),
        PrimaryKeyConstraint('symbol', name='company_overview_pkey'),
        Index('idx_company_overview_symbol', 'symbol')
    )

    symbol: Mapped[str] = mapped_column(Text, primary_key=True)
    id: Mapped[Optional[str]] = mapped_column(Text)
    issue_share: Mapped[Optional[int]] = mapped_column(BigInteger)
    history: Mapped[Optional[str]] = mapped_column(Text)
    company_profile: Mapped[Optional[str]] = mapped_column(Text)
    icb_name3: Mapped[Optional[str]] = mapped_column(Text)
    icb_name2: Mapped[Optional[str]] = mapped_column(Text)
    icb_name4: Mapped[Optional[str]] = mapped_column(Text)
    financial_ratio_issue_share: Mapped[Optional[int]] = mapped_column(BigInteger)
    charter_capital: Mapped[Optional[int]] = mapped_column(BigInteger)
    website: Mapped[Optional[str]] = mapped_column(Text)
    phone: Mapped[Optional[str]] = mapped_column(Text)
    email: Mapped[Optional[str]] = mapped_column(Text)
    address: Mapped[Optional[str]] = mapped_column(Text)
    province: Mapped[Optional[str]] = mapped_column(Text)
    founding_year: Mapped[Optional[int]] = mapped_column(Integer)
    employee_count: Mapped[Optional[int]] = mapped_column(Integer)
    fiscal_year_end: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'12/31'::text"))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))


class Events(Base):
    __tablename__ = 'events'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='events_symbol_fkey'),
        PrimaryKeyConstraint('id', name='events_pkey'),
        Index('idx_events_exright', 'exright_date'),
        Index('idx_events_public_date', 'public_date'),
        Index('idx_events_symbol', 'symbol')
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    symbol: Mapped[Optional[str]] = mapped_column(Text)
    event_title: Mapped[Optional[str]] = mapped_column(Text)
    en_event_title: Mapped[Optional[str]] = mapped_column(Text)
    public_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    issue_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    source_url: Mapped[Optional[str]] = mapped_column(Text)
    event_list_code: Mapped[Optional[str]] = mapped_column(Text)
    ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    value: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    record_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    exright_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    payment_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    event_list_name: Mapped[Optional[str]] = mapped_column(Text)
    en_event_list_name: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped[Optional['Stocks']] = relationship('Stocks', back_populates='events')


class FinancialRatios(Base):
    __tablename__ = 'financial_ratios'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='financial_ratios_symbol_fkey'),
        PrimaryKeyConstraint('id', name='financial_ratios_pkey'),
        UniqueConstraint('symbol', 'period', 'year', 'quarter', name='financial_ratios_symbol_period_year_quarter_key'),
        Index('idx_ratios_period', 'period'),
        Index('idx_ratios_symbol', 'symbol'),
        Index('idx_ratios_year', 'year')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    period: Mapped[str] = mapped_column(Text, nullable=False)
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    quarter: Mapped[Optional[int]] = mapped_column(Integer)
    price_to_book: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    price_to_earnings: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    price_to_sales: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    price_to_cash_flow: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ev_to_ebitda: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ev_to_ebit: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ev_to_sales: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    peg_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    graham_number: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    market_cap_billions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    enterprise_value_billions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    shares_outstanding_millions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    eps_vnd: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    eps_diluted_vnd: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    bvps_vnd: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    roe: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    roa: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    roic: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    roce: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    gross_margin: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ebit_margin: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ebitda_margin: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_profit_margin: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    fcf_margin: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    asset_turnover: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    fixed_asset_turnover: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    inventory_turnover: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    days_sales_outstanding: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    days_inventory_outstanding: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    days_payable_outstanding: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    cash_conversion_cycle: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    debt_to_equity: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    debt_to_equity_adjusted: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_debt_to_ebitda: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    interest_coverage_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    financial_leverage: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    fixed_assets_to_equity: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    equity_to_charter_capital: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    current_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    quick_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    cash_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    revenue_growth_yoy: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_profit_growth_yoy: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    eps_growth_yoy: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    book_value_growth_yoy: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ebitda_billions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ebit_billions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    fcf_billions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_debt_billions: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    dividend_payout_ratio: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    dividend_yield: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    dividend_per_share: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    beta: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    altman_z_score: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    data_json: Mapped[Optional[str]] = mapped_column(Text)
    source: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VCI'::text"))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='financial_ratios')


class FinancialReports(Base):
    __tablename__ = 'financial_reports'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='financial_reports_symbol_fkey'),
        PrimaryKeyConstraint('id', name='financial_reports_pkey'),
        UniqueConstraint('symbol', 'report_type', 'period', 'year', 'quarter', name='financial_reports_symbol_report_type_period_year_quarter_key'),
        Index('idx_financial_reports_period', 'period'),
        Index('idx_financial_reports_symbol', 'symbol'),
        Index('idx_financial_reports_type', 'report_type'),
        Index('idx_financial_reports_year', 'year')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    report_type: Mapped[str] = mapped_column(Text, nullable=False)
    period: Mapped[str] = mapped_column(Text, nullable=False)
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    data_json: Mapped[str] = mapped_column(Text, nullable=False)
    quarter: Mapped[Optional[int]] = mapped_column(Integer)
    source: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VCI'::text"))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='financial_reports')


class IncomeStatement(Base):
    __tablename__ = 'income_statement'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='income_statement_symbol_fkey'),
        PrimaryKeyConstraint('id', name='income_statement_pkey'),
        UniqueConstraint('symbol', 'period', 'year', 'quarter', name='income_statement_symbol_period_year_quarter_key'),
        Index('idx_income_statement_period', 'period'),
        Index('idx_income_statement_symbol', 'symbol'),
        Index('idx_income_statement_year', 'year')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    period: Mapped[str] = mapped_column(Text, nullable=False)
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    quarter: Mapped[Optional[int]] = mapped_column(Integer)
    revenue: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    revenue_growth: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_profit_parent_company: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    profit_growth: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_revenue: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    cost_of_goods_sold: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    gross_profit: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    financial_income: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    financial_expense: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_financial_income: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    operating_expenses: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    operating_profit: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    other_income: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    profit_before_tax: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    corporate_income_tax: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    deferred_income_tax: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_profit: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    minority_interest: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    net_profit_parent_company_post: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    eps: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ebitda: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ebit: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    interest_expense: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    depreciation_amortization: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    shares_diluted: Mapped[Optional[int]] = mapped_column(BigInteger)
    eps_diluted: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    data_json: Mapped[Optional[str]] = mapped_column(Text)
    source: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VCI'::text"))
    audited: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='income_statement')


class IndexPriceHistory(Base):
    __tablename__ = 'index_price_history'
    __table_args__ = (
        ForeignKeyConstraint(['index_code'], ['indices.index_code'], name='index_price_history_index_code_fkey'),
        PrimaryKeyConstraint('id', name='index_price_history_pkey'),
        UniqueConstraint('index_code', 'time', name='index_price_history_index_code_time_key'),
        Index('idx_index_price_code', 'index_code'),
        Index('idx_index_price_time', 'time')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    index_code: Mapped[str] = mapped_column(Text, nullable=False)
    time: Mapped[datetime.date] = mapped_column(Date, nullable=False)
    open: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    high: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    low: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    close: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    volume: Mapped[Optional[int]] = mapped_column(BigInteger)
    value: Mapped[Optional[int]] = mapped_column(BigInteger)
    advances: Mapped[Optional[int]] = mapped_column(Integer)
    declines: Mapped[Optional[int]] = mapped_column(Integer)
    no_changes: Mapped[Optional[int]] = mapped_column(Integer)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    indices: Mapped['Indices'] = relationship('Indices', back_populates='index_price_history')


class News(Base):
    __tablename__ = 'news'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='news_symbol_fkey'),
        PrimaryKeyConstraint('id', name='news_pkey'),
        Index('idx_news_public_date', 'public_date'),
        Index('idx_news_symbol', 'symbol')
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    symbol: Mapped[Optional[str]] = mapped_column(Text)
    news_title: Mapped[Optional[str]] = mapped_column(Text)
    news_sub_title: Mapped[Optional[str]] = mapped_column(Text)
    friendly_sub_title: Mapped[Optional[str]] = mapped_column(Text)
    news_image_url: Mapped[Optional[str]] = mapped_column(Text)
    news_source_link: Mapped[Optional[str]] = mapped_column(Text)
    public_date: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime)
    news_id: Mapped[Optional[str]] = mapped_column(Text)
    news_short_content: Mapped[Optional[str]] = mapped_column(Text)
    news_full_content: Mapped[Optional[str]] = mapped_column(Text)
    close_price: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ref_price: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    floor: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    ceiling: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    price_change_pct: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    sentiment: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped[Optional['Stocks']] = relationship('Stocks', back_populates='news')


class Officers(Base):
    __tablename__ = 'officers'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='officers_symbol_fkey'),
        PrimaryKeyConstraint('id', name='officers_pkey'),
        Index('idx_officers_status', 'status'),
        Index('idx_officers_symbol', 'symbol')
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    symbol: Mapped[Optional[str]] = mapped_column(Text)
    officer_name: Mapped[Optional[str]] = mapped_column(Text)
    officer_position: Mapped[Optional[str]] = mapped_column(Text)
    position_short_name: Mapped[Optional[str]] = mapped_column(Text)
    update_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    officer_own_percent: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    quantity: Mapped[Optional[int]] = mapped_column(BigInteger)
    status: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'working'::text"))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped[Optional['Stocks']] = relationship('Stocks', back_populates='officers')


class Portfolios(Base):
    __tablename__ = 'portfolios'
    __table_args__ = (
        ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE', name='portfolios_user_id_fkey'),
        PrimaryKeyConstraint('id', name='portfolios_pkey'),
        Index('idx_portfolios_user', 'user_id')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[Optional[str]] = mapped_column(Text)
    currency: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'VND'::text"))
    initial_cash: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, server_default=text('0'))
    is_paper_trading: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    broker: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    user: Mapped['Users'] = relationship('Users', back_populates='portfolios')
    portfolio_transactions: Mapped[list['PortfolioTransactions']] = relationship('PortfolioTransactions', back_populates='portfolio')


class PriceAlerts(Base):
    __tablename__ = 'price_alerts'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='price_alerts_symbol_fkey'),
        ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE', name='price_alerts_user_id_fkey'),
        PrimaryKeyConstraint('id', name='price_alerts_pkey'),
        Index('idx_alerts_active', 'is_active', postgresql_where='(is_active = true)'),
        Index('idx_alerts_symbol', 'symbol'),
        Index('idx_alerts_user', 'user_id')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    condition: Mapped[str] = mapped_column(Text, nullable=False)
    target_price: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    target_pct: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    note: Mapped[Optional[str]] = mapped_column(Text)
    is_active: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('true'))
    is_triggered: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    triggered_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime)
    triggered_price: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    notify_email: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('true'))
    notify_push: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='price_alerts')
    user: Mapped['Users'] = relationship('Users', back_populates='price_alerts')


class ScreenerPresets(Base):
    __tablename__ = 'screener_presets'
    __table_args__ = (
        ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE', name='screener_presets_user_id_fkey'),
        PrimaryKeyConstraint('id', name='screener_presets_pkey'),
        Index('idx_screener_public', 'is_public'),
        Index('idx_screener_user', 'user_id')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    filters_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    user_id: Mapped[Optional[int]] = mapped_column(Integer)
    description: Mapped[Optional[str]] = mapped_column(Text)
    sort_by: Mapped[Optional[str]] = mapped_column(Text)
    sort_dir: Mapped[Optional[str]] = mapped_column(Text, server_default=text("'desc'::text"))
    is_public: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    use_count: Mapped[Optional[int]] = mapped_column(Integer, server_default=text('0'))
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    user: Mapped[Optional['Users']] = relationship('Users', back_populates='screener_presets')


class Shareholders(Base):
    __tablename__ = 'shareholders'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='shareholders_symbol_fkey'),
        PrimaryKeyConstraint('id', name='shareholders_pkey'),
        Index('idx_shareholders_symbol', 'symbol'),
        Index('idx_shareholders_update_date', 'update_date')
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    symbol: Mapped[Optional[str]] = mapped_column(Text)
    share_holder: Mapped[Optional[str]] = mapped_column(Text)
    quantity: Mapped[Optional[int]] = mapped_column(BigInteger)
    share_own_percent: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    holder_type: Mapped[Optional[str]] = mapped_column(Text)
    update_date: Mapped[Optional[datetime.date]] = mapped_column(Date)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped[Optional['Stocks']] = relationship('Stocks', back_populates='shareholders')


class StockExchange(Base):
    __tablename__ = 'stock_exchange'
    __table_args__ = (
        ForeignKeyConstraint(['exchange'], ['exchanges.exchange'], name='stock_exchange_exchange_fkey'),
        ForeignKeyConstraint(['ticker'], ['stocks.ticker'], name='stock_exchange_ticker_fkey'),
        PrimaryKeyConstraint('ticker', 'exchange', name='stock_exchange_pkey'),
        Index('idx_stock_exchange_exchange', 'exchange'),
        Index('idx_stock_exchange_ticker', 'ticker')
    )

    ticker: Mapped[str] = mapped_column(Text, primary_key=True)
    exchange: Mapped[str] = mapped_column(Text, primary_key=True)
    id: Mapped[Optional[int]] = mapped_column(Integer)
    type: Mapped[Optional[str]] = mapped_column(Text)

    exchanges: Mapped['Exchanges'] = relationship('Exchanges', back_populates='stock_exchange')
    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='stock_exchange')


t_stock_index = Table(
    'stock_index', Base.metadata,
    Column('ticker', Text, primary_key=True),
    Column('index_code', Text, primary_key=True),
    ForeignKeyConstraint(['index_code'], ['indices.index_code'], name='stock_index_index_code_fkey'),
    ForeignKeyConstraint(['ticker'], ['stocks.ticker'], name='stock_index_ticker_fkey'),
    PrimaryKeyConstraint('ticker', 'index_code', name='stock_index_pkey'),
    Index('idx_stock_index_index', 'index_code'),
    Index('idx_stock_index_ticker', 'ticker')
)


class StockIndustry(Base):
    __tablename__ = 'stock_industry'
    __table_args__ = (
        ForeignKeyConstraint(['icb_code'], ['industries.icb_code'], name='stock_industry_icb_code_fkey'),
        ForeignKeyConstraint(['ticker'], ['stocks.ticker'], name='stock_industry_ticker_fkey'),
        PrimaryKeyConstraint('ticker', 'icb_code', name='stock_industry_pkey'),
        Index('idx_stock_industry_icb', 'icb_code'),
        Index('idx_stock_industry_ticker', 'ticker')
    )

    ticker: Mapped[str] = mapped_column(Text, primary_key=True)
    icb_code: Mapped[str] = mapped_column(Text, primary_key=True)
    icb_name2: Mapped[Optional[str]] = mapped_column(Text)
    en_icb_name2: Mapped[Optional[str]] = mapped_column(Text)
    icb_name3: Mapped[Optional[str]] = mapped_column(Text)
    en_icb_name3: Mapped[Optional[str]] = mapped_column(Text)
    icb_name4: Mapped[Optional[str]] = mapped_column(Text)
    en_icb_name4: Mapped[Optional[str]] = mapped_column(Text)
    icb_code1: Mapped[Optional[str]] = mapped_column(Text)
    icb_code2: Mapped[Optional[str]] = mapped_column(Text)
    icb_code3: Mapped[Optional[str]] = mapped_column(Text)
    icb_code4: Mapped[Optional[str]] = mapped_column(Text)

    industries: Mapped['Industries'] = relationship('Industries', back_populates='stock_industry')
    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='stock_industry')


class Subsidiaries(Base):
    __tablename__ = 'subsidiaries'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='subsidiaries_symbol_fkey'),
        PrimaryKeyConstraint('id', name='subsidiaries_pkey'),
        Index('idx_subsidiaries_symbol', 'symbol')
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    symbol: Mapped[Optional[str]] = mapped_column(Text)
    sub_organ_code: Mapped[Optional[str]] = mapped_column(Text)
    ownership_percent: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    organ_name: Mapped[Optional[str]] = mapped_column(Text)
    type: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped[Optional['Stocks']] = relationship('Stocks', back_populates='subsidiaries')


class UpdateLog(Base):
    __tablename__ = 'update_log'
    __table_args__ = (
        ForeignKeyConstraint(['sync_config_id'], ['sync_config.id'], name='update_log_sync_config_id_fkey'),
        PrimaryKeyConstraint('id', name='update_log_pkey'),
        Index('idx_update_log_status', 'status'),
        Index('idx_update_log_table', 'table_name'),
        Index('idx_update_log_time', 'update_time')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    table_name: Mapped[Optional[str]] = mapped_column(Text)
    source: Mapped[Optional[str]] = mapped_column(Text)
    records_inserted: Mapped[Optional[int]] = mapped_column(Integer, server_default=text('0'))
    records_updated: Mapped[Optional[int]] = mapped_column(Integer, server_default=text('0'))
    records_skipped: Mapped[Optional[int]] = mapped_column(Integer, server_default=text('0'))
    total_records: Mapped[Optional[int]] = mapped_column(Integer, server_default=text('0'))
    duration_ms: Mapped[Optional[int]] = mapped_column(Integer)
    status: Mapped[Optional[str]] = mapped_column(Text)
    error_message: Mapped[Optional[str]] = mapped_column(Text)
    sync_config_id: Mapped[Optional[int]] = mapped_column(Integer)
    update_time: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    sync_config: Mapped[Optional['SyncConfig']] = relationship('SyncConfig', back_populates='update_log')


class Watchlists(Base):
    __tablename__ = 'watchlists'
    __table_args__ = (
        ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE', name='watchlists_user_id_fkey'),
        PrimaryKeyConstraint('id', name='watchlists_pkey'),
        Index('idx_watchlists_user', 'user_id')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    is_default: Mapped[Optional[bool]] = mapped_column(Boolean, server_default=text('false'))
    color: Mapped[Optional[str]] = mapped_column(Text)
    description: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))
    updated_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    user: Mapped['Users'] = relationship('Users', back_populates='watchlists')
    watchlist_items: Mapped[list['WatchlistItems']] = relationship('WatchlistItems', back_populates='watchlist')


class PortfolioTransactions(Base):
    __tablename__ = 'portfolio_transactions'
    __table_args__ = (
        ForeignKeyConstraint(['portfolio_id'], ['portfolios.id'], ondelete='CASCADE', name='portfolio_transactions_portfolio_id_fkey'),
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='portfolio_transactions_symbol_fkey'),
        PrimaryKeyConstraint('id', name='portfolio_transactions_pkey'),
        Index('idx_portfolio_txn_date', 'txn_date'),
        Index('idx_portfolio_txn_portfolio', 'portfolio_id'),
        Index('idx_portfolio_txn_symbol', 'symbol')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    portfolio_id: Mapped[int] = mapped_column(Integer, nullable=False)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    type: Mapped[str] = mapped_column(Text, nullable=False)
    quantity: Mapped[int] = mapped_column(BigInteger, nullable=False)
    price: Mapped[decimal.Decimal] = mapped_column(Numeric, nullable=False)
    txn_date: Mapped[datetime.date] = mapped_column(Date, nullable=False)
    fee: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, server_default=text('0'))
    tax: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, server_default=text('0'))
    total_amount: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric, Computed('((((quantity)::numeric * price) + fee) + tax)', persisted=True))
    note: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    portfolio: Mapped['Portfolios'] = relationship('Portfolios', back_populates='portfolio_transactions')
    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='portfolio_transactions')


class WatchlistItems(Base):
    __tablename__ = 'watchlist_items'
    __table_args__ = (
        ForeignKeyConstraint(['symbol'], ['stocks.ticker'], name='watchlist_items_symbol_fkey'),
        ForeignKeyConstraint(['watchlist_id'], ['watchlists.id'], ondelete='CASCADE', name='watchlist_items_watchlist_id_fkey'),
        PrimaryKeyConstraint('id', name='watchlist_items_pkey'),
        UniqueConstraint('watchlist_id', 'symbol', name='watchlist_items_watchlist_id_symbol_key'),
        Index('idx_watchlist_items_symbol', 'symbol'),
        Index('idx_watchlist_items_watchlist', 'watchlist_id')
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    watchlist_id: Mapped[int] = mapped_column(Integer, nullable=False)
    symbol: Mapped[str] = mapped_column(Text, nullable=False)
    note: Mapped[Optional[str]] = mapped_column(Text)
    target_price: Mapped[Optional[decimal.Decimal]] = mapped_column(Numeric)
    added_at: Mapped[Optional[datetime.datetime]] = mapped_column(DateTime, server_default=text('CURRENT_TIMESTAMP'))

    stocks: Mapped['Stocks'] = relationship('Stocks', back_populates='watchlist_items')
    watchlist: Mapped['Watchlists'] = relationship('Watchlists', back_populates='watchlist_items')
