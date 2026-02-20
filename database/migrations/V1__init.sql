-- public.balance_sheet definition

-- Drop table

-- DROP TABLE public.balance_sheet;

CREATE TABLE IF NOT EXISTS public.balance_sheet (
	id int4 NULL,
	symbol text NOT NULL,
	"period" text NOT NULL,
	"year" int4 NOT NULL,
	quarter int4 NULL,
	asset_current float4 NULL,
	cash_and_equivalents float4 NULL,
	short_term_investments float4 NULL,
	accounts_receivable float4 NULL,
	inventory float4 NULL,
	current_assets_other float4 NULL,
	asset_non_current float4 NULL,
	long_term_receivables float4 NULL,
	fixed_assets float4 NULL,
	long_term_investments float4 NULL,
	non_current_assets_other float4 NULL,
	total_assets float4 NULL,
	liabilities_total float4 NULL,
	liabilities_current float4 NULL,
	liabilities_non_current float4 NULL,
	equity_total float4 NULL,
	share_capital float4 NULL,
	retained_earnings float4 NULL,
	equity_other float4 NULL,
	total_equity_and_liabilities float4 NULL,
	data_json text NULL,
	"source" text NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.cash_flow_statement definition

-- Drop table

-- DROP TABLE public.cash_flow_statement;

CREATE TABLE IF NOT EXISTS public.cash_flow_statement (
	id int4 NULL,
	symbol text NOT NULL,
	"period" text NOT NULL,
	"year" int4 NOT NULL,
	quarter int4 NULL,
	profit_before_tax float4 NULL,
	depreciation_fixed_assets float4 NULL,
	provision_credit_loss_real_estate float4 NULL,
	profit_loss_from_disposal_fixed_assets float4 NULL,
	profit_loss_investment_activities float4 NULL,
	interest_income float4 NULL,
	interest_and_dividend_income float4 NULL,
	net_cash_flow_from_operating_activities_before_working_capital float4 NULL,
	increase_decrease_receivables float4 NULL,
	increase_decrease_inventory float4 NULL,
	increase_decrease_payables float4 NULL,
	increase_decrease_prepaid_expenses float4 NULL,
	interest_expense_paid float4 NULL,
	corporate_income_tax_paid float4 NULL,
	other_cash_from_operating_activities float4 NULL,
	other_cash_paid_for_operating_activities float4 NULL,
	net_cash_from_operating_activities float4 NULL,
	purchase_purchase_fixed_assets float4 NULL,
	proceeds_from_disposal_fixed_assets float4 NULL,
	loans_other_collections float4 NULL,
	investments_other_companies float4 NULL,
	proceeds_from_sale_investments_other_companies float4 NULL,
	dividends_and_profits_received float4 NULL,
	net_cash_from_investing_activities float4 NULL,
	increase_share_capital_contribution_equity float4 NULL,
	payment_for_capital_contribution_buyback_shares float4 NULL,
	proceeds_from_borrowings float4 NULL,
	repayments_of_borrowings float4 NULL,
	lease_principal_payments float4 NULL,
	dividends_paid float4 NULL,
	other_cash_from_financing_activities float4 NULL,
	net_cash_from_financing_activities float4 NULL,
	net_cash_flow_period float4 NULL,
	cash_and_cash_equivalents_beginning float4 NULL,
	cash_and_cash_equivalents_ending float4 NULL,
	data_json text NULL,
	"source" text NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.company_overview definition

-- Drop table

-- DROP TABLE public.company_overview;

CREATE TABLE IF NOT EXISTS public.company_overview (
	symbol text NULL,
	id text NULL,
	issue_share int4 NULL,
	history text NULL,
	company_profile text NULL,
	icb_name3 text NULL,
	icb_name2 text NULL,
	icb_name4 text NULL,
	financial_ratio_issue_share int4 NULL,
	charter_capital int4 NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.events definition

-- Drop table

-- DROP TABLE public.events;

CREATE TABLE IF NOT EXISTS public.events (
	id text NULL,
	symbol text NULL,
	event_title text NULL,
	en_event_title text NULL,
	public_date text NULL,
	issue_date text NULL,
	source_url text NULL,
	event_list_code text NULL,
	ratio float4 NULL,
	value float4 NULL,
	record_date text NULL,
	exright_date text NULL,
	event_list_name text NULL,
	en_event_list_name text NULL,
	created_at text NULL
);


-- public.exchanges definition

-- Drop table

-- DROP TABLE public.exchanges;

CREATE TABLE IF NOT EXISTS public.exchanges (
	exchange text NULL,
	exchange_name text NULL,
	exchange_code text NULL,
	created_at text NULL
);


-- public.financial_ratios definition

-- Drop table

-- DROP TABLE public.financial_ratios;

CREATE TABLE IF NOT EXISTS public.financial_ratios (
	id int4 NULL,
	symbol text NOT NULL,
	"period" text NOT NULL,
	"year" int4 NOT NULL,
	quarter int4 NULL,
	price_to_book float4 NULL,
	market_cap_billions float4 NULL,
	shares_outstanding_millions float4 NULL,
	price_to_earnings float4 NULL,
	price_to_sales float4 NULL,
	price_to_cash_flow float4 NULL,
	eps_vnd float4 NULL,
	bvps_vnd float4 NULL,
	ev_to_ebitda float4 NULL,
	debt_to_equity float4 NULL,
	debt_to_equity_adjusted float4 NULL,
	fixed_assets_to_equity float4 NULL,
	equity_to_charter_capital float4 NULL,
	asset_turnover float4 NULL,
	fixed_asset_turnover float4 NULL,
	days_sales_outstanding float4 NULL,
	days_inventory_outstanding float4 NULL,
	days_payable_outstanding float4 NULL,
	cash_conversion_cycle float4 NULL,
	inventory_turnover float4 NULL,
	ebit_margin float4 NULL,
	gross_margin float4 NULL,
	net_profit_margin float4 NULL,
	roe float4 NULL,
	roic float4 NULL,
	roa float4 NULL,
	ebitda_billions float4 NULL,
	ebit_billions float4 NULL,
	dividend_payout_ratio float4 NULL,
	current_ratio float4 NULL,
	quick_ratio float4 NULL,
	cash_ratio float4 NULL,
	interest_coverage_ratio float4 NULL,
	financial_leverage float4 NULL,
	beta float4 NULL,
	ev_to_ebit float4 NULL,
	data_json text NULL,
	"source" text NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.financial_reports definition

-- Drop table

-- DROP TABLE public.financial_reports;

CREATE TABLE IF NOT EXISTS public.financial_reports (
	id int4 NULL,
	symbol text NOT NULL,
	report_type text NOT NULL,
	"period" text NOT NULL,
	"year" int4 NOT NULL,
	quarter int4 NULL,
	data_json text NOT NULL,
	"source" text NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.income_statement definition

-- Drop table

-- DROP TABLE public.income_statement;

CREATE TABLE IF NOT EXISTS public.income_statement (
	id int4 NULL,
	symbol text NOT NULL,
	"period" text NOT NULL,
	"year" int4 NOT NULL,
	quarter int4 NULL,
	revenue float4 NULL,
	revenue_growth float4 NULL,
	net_profit_parent_company float4 NULL,
	profit_growth float4 NULL,
	net_revenue float4 NULL,
	cost_of_goods_sold float4 NULL,
	gross_profit float4 NULL,
	financial_income float4 NULL,
	financial_expense float4 NULL,
	net_financial_income float4 NULL,
	operating_expenses float4 NULL,
	operating_profit float4 NULL,
	other_income float4 NULL,
	profit_before_tax float4 NULL,
	corporate_income_tax float4 NULL,
	deferred_income_tax float4 NULL,
	net_profit float4 NULL,
	minority_interest float4 NULL,
	net_profit_parent_company_post float4 NULL,
	eps float4 NULL,
	data_json text NULL,
	"source" text NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.indices definition

-- Drop table

-- DROP TABLE public.indices;

CREATE TABLE IF NOT EXISTS public.indices (
	index_code text NULL,
	index_name text NULL,
	description text NULL,
	group_name text NULL,
	index_id int4 NULL,
	sector_id float4 NULL,
	created_at text NULL
);


-- public.industries definition

-- Drop table

-- DROP TABLE public.industries;

CREATE TABLE IF NOT EXISTS public.industries (
	icb_code text NULL,
	icb_name text NULL,
	en_icb_name text NULL,
	"level" int4 NULL,
	created_at text NULL
);


-- public.news definition

-- Drop table

-- DROP TABLE public.news;

CREATE TABLE IF NOT EXISTS public.news (
	id text NULL,
	symbol text NULL,
	news_title text NULL,
	news_sub_title text NULL,
	friendly_sub_title text NULL,
	news_image_url text NULL,
	news_source_link text NULL,
	public_date int4 NULL,
	news_id text NULL,
	news_short_content text NULL,
	news_full_content text NULL,
	close_price int4 NULL,
	ref_price int4 NULL,
	floor int4 NULL,
	"ceiling" int4 NULL,
	price_change_pct float4 NULL,
	created_at text NULL
);


-- public.officers definition

-- Drop table

-- DROP TABLE public.officers;

CREATE TABLE IF NOT EXISTS public.officers (
	id text NULL,
	symbol text NULL,
	officer_name text NULL,
	officer_position text NULL,
	position_short_name text NULL,
	update_date text NULL,
	officer_own_percent float4 NULL,
	quantity int4 NULL,
	status text NULL,
	created_at text NULL
);


-- public.shareholders definition

-- Drop table

-- DROP TABLE public.shareholders;

CREATE TABLE IF NOT EXISTS public.shareholders (
	id text NULL,
	symbol text NULL,
	share_holder text NULL,
	quantity int4 NULL,
	share_own_percent float4 NULL,
	update_date text NULL,
	created_at text NULL
);


-- public.stock_exchange definition

-- Drop table

-- DROP TABLE public.stock_exchange;

CREATE TABLE IF NOT EXISTS public.stock_exchange (
	ticker text NULL,
	exchange text NULL,
	id int4 NULL,
	"type" text NULL
);


-- public.stock_index definition

-- Drop table

-- DROP TABLE public.stock_index;

CREATE TABLE IF NOT EXISTS public.stock_index (
	ticker text NULL,
	index_code text NULL
);


-- public.stock_industry definition

-- Drop table

-- DROP TABLE public.stock_industry;

CREATE TABLE IF NOT EXISTS public.stock_industry (
	ticker text NULL,
	icb_code text NULL,
	icb_name2 text NULL,
	en_icb_name2 text NULL,
	icb_name3 text NULL,
	en_icb_name3 text NULL,
	icb_name4 text NULL,
	en_icb_name4 text NULL,
	icb_code1 text NULL,
	icb_code2 text NULL,
	icb_code3 text NULL,
	icb_code4 text NULL
);


-- public.stock_intraday definition

-- Drop table

-- DROP TABLE public.stock_intraday;

CREATE TABLE IF NOT EXISTS public.stock_intraday (
	symbol text NULL,
	"time" text NULL,
	price float4 NULL,
	accumulated_val int4 NULL,
	accumulated_vol int4 NULL,
	volume int4 NULL,
	match_type text NULL
);


-- public.stock_price_history definition

-- Drop table

-- DROP TABLE public.stock_price_history;

CREATE TABLE IF NOT EXISTS public.stock_price_history (
	id int4 NULL,
	symbol text NOT NULL,
	"time" text NOT NULL,
	"open" float4 NULL,
	high float4 NULL,
	low float4 NULL,
	"close" float4 NULL,
	volume int4 NULL,
	created_at text NULL
);


-- public.stocks definition

-- Drop table

-- DROP TABLE public.stocks;

CREATE TABLE IF NOT EXISTS public.stocks (
	ticker text NULL,
	organ_name text NULL,
	en_organ_name text NULL,
	organ_short_name text NULL,
	en_organ_short_name text NULL,
	com_type_code text NULL,
	status text NULL,
	listed_date text NULL,
	delisted_date text NULL,
	company_id text NULL,
	tax_code text NULL,
	isin text NULL,
	created_at text NULL,
	updated_at text NULL
);


-- public.subsidiaries definition

-- Drop table

-- DROP TABLE public.subsidiaries;

CREATE TABLE IF NOT EXISTS public.subsidiaries (
	id text NULL,
	symbol text NULL,
	sub_organ_code text NULL,
	ownership_percent float4 NULL,
	organ_name text NULL,
	"type" text NULL,
	created_at text NULL
);


-- public.update_log definition

-- Drop table

-- DROP TABLE public.update_log;

CREATE TABLE IF NOT EXISTS public.update_log (
	id int4 NULL,
	table_name text NULL,
	records_updated int4 NULL,
	update_time text NULL,
	status text NULL
);