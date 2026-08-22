begin;

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
create extension if not exists citext with schema extensions;

create type public.market_code as enum ('KOSPI', 'KOSDAQ');
create type public.instrument_kind as enum ('COMMON', 'PREFERRED', 'ETF', 'ETN', 'SPAC', 'OTHER');
create type public.universe_kind as enum ('KOSPI_200', 'KOSDAQ_150', 'KOSPI_ALL', 'KOSDAQ_ALL', 'KR_ALL', 'CUSTOM');
create type public.timeframe as enum ('D1');
create type public.rule_logic as enum ('ALL', 'ANY');
create type public.portfolio_kind as enum ('MANUAL_LIVE', 'SANDBOX_PAPER', 'RANKED_PAPER');
create type public.position_status as enum ('OPEN', 'EXIT_PENDING', 'PARTIALLY_CLOSED', 'CLOSED', 'ARCHIVED');
create type public.order_side as enum ('BUY', 'SELL');
create type public.paper_order_status as enum ('PENDING', 'FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED');
create type public.paper_order_event_type as enum ('SUBMITTED', 'CANCELLED', 'FILLED', 'REJECTED', 'EXPIRED', 'RETRY_SCHEDULED');
create type public.execution_event_type as enum ('EXECUTION', 'REVERSAL', 'CORRECTION');
create type public.cash_event_type as enum ('INITIAL_CAPITAL', 'DEPOSIT', 'WITHDRAWAL', 'BUY_SETTLEMENT', 'SELL_SETTLEMENT', 'FEE', 'TAX', 'DIVIDEND', 'REVERSAL', 'CORRECTION');
create type public.adjustment_type as enum ('SPLIT', 'MERGER', 'DIVIDEND', 'SPIN_OFF', 'OTHER');
create type public.corporate_action_type as enum ('SPLIT', 'REVERSE_SPLIT', 'CASH_DIVIDEND', 'STOCK_DIVIDEND', 'RIGHTS', 'MERGER', 'SPIN_OFF', 'OTHER');
create type public.signal_type as enum ('BUY_CONDITION', 'SELL_CONDITION', 'REGISTRATION_STATE');
create type public.position_signal_status as enum ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED_BY_CONDITION', 'RESOLVED_BY_EXECUTION', 'CANCELLED_BY_POSITION_CLOSE');
create type public.outbox_status as enum ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED');
create type public.backtest_status as enum ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED');
create type public.ranking_snapshot_kind as enum ('COMBINATION', 'USER');
create type public.ranking_period as enum ('M3', 'M6', 'Y1', 'ALL', 'BETA');
create type public.ranking_track_status as enum ('ACTIVE', 'ENDED', 'COOLDOWN');
create type public.ranking_track_event_type as enum ('STARTED', 'NAV_RECORDED', 'ORDER_CREATED', 'ORDER_EXPIRED', 'POSITION_OPENED', 'POSITION_CLOSED', 'ENDED', 'CORRECTION');
create type public.entitlement_source as enum ('PLAN', 'GRANT', 'ADMIN', 'MIGRATION');
create type public.report_status as enum ('OPEN', 'REVIEWING', 'RESOLVED', 'REJECTED');

create table public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  public_profile_id uuid not null default gen_random_uuid() unique,
  nickname extensions.citext not null unique,
  is_public boolean not null default false,
  risk_disclosure_accepted_at timestamptz,
  terms_version text,
  privacy_version text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (char_length(nickname::text) between 2 and 24)
);

create table public.instruments (
  id uuid primary key default gen_random_uuid(),
  symbol text not null unique,
  name_ko text not null,
  market public.market_code not null,
  kind public.instrument_kind not null,
  isin text unique,
  listed_on date,
  delisted_on date,
  is_managed boolean not null default false,
  is_trade_suspended boolean not null default false,
  provider_refs jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (symbol ~ '^[0-9A-Z]{6,12}$'),
  check (delisted_on is null or listed_on is null or delisted_on >= listed_on)
);

create table public.universe_definitions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  kind public.universe_kind not null,
  name_ko text not null,
  description text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((kind = 'CUSTOM') = (user_id is not null))
);

create unique index universe_definitions_system_kind_uidx
  on public.universe_definitions(kind) where user_id is null;
create unique index universe_definitions_user_name_uidx
  on public.universe_definitions(user_id, lower(name_ko)) where user_id is not null;

create table public.universe_versions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  universe_definition_id uuid not null references public.universe_definitions(id) on delete cascade,
  version integer not null check (version > 0),
  effective_from date not null,
  effective_to date,
  inclusion_policy jsonb not null default '{}'::jsonb,
  source text not null,
  source_revision text,
  finalized_at timestamptz,
  created_at timestamptz not null default now(),
  unique (universe_definition_id, version),
  check (effective_to is null or effective_to >= effective_from)
);

create table public.universe_memberships (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  universe_version_id uuid not null references public.universe_versions(id) on delete cascade,
  instrument_id uuid not null references public.instruments(id),
  effective_from date not null,
  effective_to date,
  created_at timestamptz not null default now(),
  unique (universe_version_id, instrument_id, effective_from),
  check (effective_to is null or effective_to >= effective_from)
);

create table public.market_sessions (
  id uuid primary key default gen_random_uuid(),
  calendar_version text not null,
  market public.market_code not null,
  session_date date not null,
  is_trading_day boolean not null,
  open_at timestamptz,
  close_at timestamptz,
  order_cutoff_at timestamptz,
  note text,
  created_at timestamptz not null default now(),
  unique (calendar_version, market, session_date),
  check (
    (is_trading_day and open_at is not null and close_at is not null and close_at > open_at)
    or (not is_trading_day and open_at is null and close_at is null)
  ),
  check (order_cutoff_at is null or open_at is null or order_cutoff_at <= open_at)
);

create table public.candles (
  id uuid primary key default gen_random_uuid(),
  instrument_id uuid not null references public.instruments(id),
  timeframe public.timeframe not null default 'D1',
  session_date date not null,
  open_at timestamptz not null,
  close_at timestamptz not null,
  open numeric(24,6) not null,
  high numeric(24,6) not null,
  low numeric(24,6) not null,
  close numeric(24,6) not null,
  adjusted_close numeric(24,6),
  volume numeric(30,0),
  is_final boolean not null default false,
  is_stale boolean not null default false,
  provider text not null,
  provider_revision text,
  dataset_version text not null,
  received_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (instrument_id, timeframe, close_at),
  check (open > 0 and high > 0 and low > 0 and close > 0),
  check (high >= greatest(open, close, low)),
  check (low <= least(open, close, high)),
  check (adjusted_close is null or adjusted_close > 0),
  check (volume is null or volume >= 0),
  check (close_at > open_at)
);

create table public.indicator_definitions (
  id uuid primary key default gen_random_uuid(),
  code text not null,
  version integer not null check (version > 0),
  name_ko text not null,
  short_description_ko text not null,
  formula_ko text not null,
  default_params jsonb not null,
  minimum_bars integer not null check (minimum_bars > 0),
  signal_definition jsonb not null,
  common_misconception_ko text not null,
  weakness_ko text not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (code, version),
  check (jsonb_typeof(default_params) = 'object'),
  check (jsonb_typeof(signal_definition) = 'object')
);

create table public.strategies (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  is_public boolean not null default false,
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.strategy_versions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  strategy_id uuid not null references public.strategies(id) on delete cascade,
  version integer not null check (version > 0),
  universe_version_id uuid not null references public.universe_versions(id),
  timeframe public.timeframe not null default 'D1',
  root_logic public.rule_logic not null default 'ALL',
  notifications_enabled boolean not null default true,
  cooldown_minutes integer not null default 0 check (cooldown_minutes >= 0),
  engine_version text not null,
  finalized_at timestamptz,
  created_at timestamptz not null default now(),
  unique (strategy_id, version)
);

create table public.strategy_rules (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  strategy_version_id uuid not null references public.strategy_versions(id) on delete cascade,
  rule_index smallint not null check (rule_index between 1 and 5),
  group_key text not null default 'root',
  group_logic public.rule_logic not null default 'ALL',
  indicator_definition_id uuid not null references public.indicator_definitions(id),
  operator text not null check (operator in ('GT', 'GTE', 'LT', 'LTE', 'EQ', 'CROSSES_ABOVE', 'CROSSES_BELOW', 'IS_TRUE')),
  params jsonb not null default '{}'::jsonb,
  compare_value numeric(24,10),
  created_at timestamptz not null default now(),
  unique (strategy_version_id, rule_index),
  check (jsonb_typeof(params) = 'object')
);

create table public.watchlist_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  instrument_id uuid not null references public.instruments(id),
  created_at timestamptz not null default now(),
  unique (user_id, instrument_id)
);

create table public.paper_fill_model_versions (
  id uuid primary key default gen_random_uuid(),
  code text not null,
  version integer not null check (version > 0),
  slippage_buy_bps numeric(12,6) not null check (slippage_buy_bps >= 0),
  slippage_sell_bps numeric(12,6) not null check (slippage_sell_bps >= 0),
  spread_bps numeric(12,6) not null check (spread_bps >= 0),
  tick_rule_version text not null,
  configuration jsonb not null default '{}'::jsonb,
  effective_from timestamptz not null,
  created_at timestamptz not null default now(),
  unique (code, version)
);

create table public.cost_model_versions (
  id uuid primary key default gen_random_uuid(),
  code text not null,
  version integer not null check (version > 0),
  buy_fee_rate numeric(18,10) not null check (buy_fee_rate >= 0),
  sell_fee_rate numeric(18,10) not null check (sell_fee_rate >= 0),
  sell_tax_rate numeric(18,10) not null check (sell_tax_rate >= 0),
  configuration jsonb not null default '{}'::jsonb,
  effective_from timestamptz not null,
  created_at timestamptz not null default now(),
  unique (code, version)
);

create table public.portfolios (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  kind public.portfolio_kind not null,
  name text not null,
  currency text not null default 'KRW' check (currency = 'KRW'),
  initial_cash numeric(24,6) not null default 0 check (initial_cash >= 0),
  cash_balance numeric(24,6) not null default 0 check (cash_balance >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  archived_at timestamptz,
  unique (user_id, kind, name)
);

create table public.sell_rule_sets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.sell_rule_versions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  sell_rule_set_id uuid not null references public.sell_rule_sets(id) on delete cascade,
  version integer not null check (version > 0),
  stop_loss_rate numeric(18,10),
  take_profit_rate numeric(18,10),
  trailing_stop_rate numeric(18,10),
  max_holding_sessions integer,
  technical_logic public.rule_logic,
  technical_conditions jsonb not null default '[]'::jsonb,
  manual_only boolean not null default false,
  cost_basis_method text not null default 'MOVING_WEIGHTED_AVERAGE' check (cost_basis_method = 'MOVING_WEIGHTED_AVERAGE'),
  finalized_at timestamptz,
  created_at timestamptz not null default now(),
  unique (sell_rule_set_id, version),
  check (stop_loss_rate is null or (stop_loss_rate > 0 and stop_loss_rate < 1)),
  check (take_profit_rate is null or take_profit_rate > 0),
  check (trailing_stop_rate is null or (trailing_stop_rate > 0 and trailing_stop_rate < 1)),
  check (max_holding_sessions is null or max_holding_sessions > 0),
  check (jsonb_typeof(technical_conditions) = 'array'),
  check (jsonb_array_length(technical_conditions) <= 3),
  check ((jsonb_array_length(technical_conditions) = 0 and technical_logic is null) or (jsonb_array_length(technical_conditions) > 0 and technical_logic is not null)),
  check (
    manual_only = (
      stop_loss_rate is null and take_profit_rate is null and trailing_stop_rate is null
      and max_holding_sessions is null and jsonb_array_length(technical_conditions) = 0
    )
  )
);

create table public.signals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  strategy_version_id uuid not null references public.strategy_versions(id),
  instrument_id uuid not null references public.instruments(id),
  timeframe public.timeframe not null default 'D1',
  candle_close_at timestamptz not null,
  signal_type public.signal_type not null default 'BUY_CONDITION' check (signal_type = 'BUY_CONDITION'),
  signal_strength numeric(18,10),
  prior_liquidity_score numeric(24,10),
  evidence jsonb not null,
  dataset_version text not null,
  engine_version text not null,
  data_is_stale boolean not null default false check (not data_is_stale),
  created_at timestamptz not null default now(),
  unique (strategy_version_id, instrument_id, timeframe, candle_close_at, signal_type),
  check (jsonb_typeof(evidence) = 'object')
);

create table public.positions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  portfolio_id uuid not null references public.portfolios(id),
  portfolio_kind public.portfolio_kind not null,
  instrument_id uuid not null references public.instruments(id),
  status public.position_status not null,
  quantity bigint not null default 0 check (quantity >= 0),
  average_cost numeric(24,6) not null default 0 check (average_cost >= 0),
  realized_pnl numeric(24,6) not null default 0,
  highest_completed_close numeric(24,6),
  opened_at timestamptz not null,
  first_execution_at timestamptz not null,
  closed_at timestamptz,
  strategy_version_id uuid references public.strategy_versions(id),
  buy_signal_id uuid references public.signals(id),
  universe_version_id uuid references public.universe_versions(id),
  sell_rule_version_id uuid references public.sell_rule_versions(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (highest_completed_close is null or highest_completed_close > 0),
  check ((status in ('CLOSED', 'ARCHIVED') and quantity = 0 and closed_at is not null) or (status not in ('CLOSED', 'ARCHIVED') and quantity > 0 and closed_at is null)),
  check (first_execution_at >= opened_at)
);

create unique index positions_ranked_one_open_instrument_uidx
  on public.positions(portfolio_id, instrument_id)
  where portfolio_kind = 'RANKED_PAPER' and status in ('OPEN', 'EXIT_PENDING', 'PARTIALLY_CLOSED');
create index positions_active_monitor_idx
  on public.positions(instrument_id, status)
  where status in ('OPEN', 'PARTIALLY_CLOSED');

create table public.position_sell_rule_bindings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  position_id uuid not null references public.positions(id) on delete cascade,
  sell_rule_version_id uuid not null references public.sell_rule_versions(id),
  effective_from timestamptz not null,
  effective_to timestamptz,
  initialized_candle_close_at timestamptz,
  created_at timestamptz not null default now(),
  unique (position_id, effective_from),
  check (effective_to is null or effective_to > effective_from)
);

create unique index position_sell_rule_one_active_uidx
  on public.position_sell_rule_bindings(position_id) where effective_to is null;

create table public.position_signals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  position_id uuid not null references public.positions(id) on delete cascade,
  sell_rule_version_id uuid not null references public.sell_rule_versions(id),
  candle_close_at timestamptz not null,
  signal_type public.signal_type not null default 'SELL_CONDITION' check (signal_type in ('SELL_CONDITION', 'REGISTRATION_STATE')),
  status public.position_signal_status not null default 'ACTIVE',
  reference_close numeric(24,6) not null check (reference_close > 0),
  average_cost numeric(24,6) not null check (average_cost >= 0),
  net_return_rate numeric(18,10) not null,
  remaining_quantity bigint not null check (remaining_quantity > 0),
  data_is_stale boolean not null default false check (not data_is_stale),
  registration_already_triggered boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (position_id, sell_rule_version_id, candle_close_at, signal_type)
);

create index position_signals_active_idx
  on public.position_signals(position_id, created_at desc) where status = 'ACTIVE';

create table public.signal_rule_matches (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  position_signal_id uuid not null references public.position_signals(id) on delete cascade,
  rule_key text not null,
  rule_kind text not null check (rule_kind in ('STOP_LOSS', 'TAKE_PROFIT', 'TRAILING_STOP', 'MAX_HOLDING_DAYS', 'TECHNICAL', 'REGISTRATION_STATE')),
  evidence jsonb not null,
  created_at timestamptz not null default now(),
  unique (position_signal_id, rule_key),
  check (jsonb_typeof(evidence) = 'object')
);

create table public.paper_orders (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  portfolio_id uuid not null references public.portfolios(id),
  portfolio_kind public.portfolio_kind not null check (portfolio_kind in ('SANDBOX_PAPER', 'RANKED_PAPER')),
  position_id uuid references public.positions(id),
  instrument_id uuid not null references public.instruments(id),
  side public.order_side not null,
  quantity bigint not null check (quantity > 0),
  status public.paper_order_status not null default 'PENDING',
  scheduled_market_session_id uuid not null references public.market_sessions(id),
  source_signal_id uuid references public.signals(id),
  source_position_signal_id uuid references public.position_signals(id),
  fill_model_version_id uuid not null references public.paper_fill_model_versions(id),
  cost_model_version_id uuid not null references public.cost_model_versions(id),
  can_user_cancel boolean not null,
  idempotency_key text not null,
  rejection_reason text,
  submitted_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (portfolio_id, idempotency_key),
  check (portfolio_kind <> 'RANKED_PAPER' or not can_user_cancel),
  check (portfolio_kind <> 'RANKED_PAPER' or (side = 'BUY' and source_signal_id is not null) or (side = 'SELL' and source_position_signal_id is not null)),
  check ((side = 'BUY' and source_position_signal_id is null) or side = 'SELL'),
  check (side <> 'SELL' or position_id is not null)
);

create index paper_orders_due_idx
  on public.paper_orders(scheduled_market_session_id, status) where status = 'PENDING';

create table public.paper_order_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  paper_order_id uuid not null references public.paper_orders(id),
  event_type public.paper_order_event_type not null,
  reason_code text,
  payload jsonb not null default '{}'::jsonb,
  idempotency_key text not null,
  occurred_at timestamptz not null default now(),
  unique (paper_order_id, idempotency_key),
  check (jsonb_typeof(payload) = 'object')
);

create table public.position_executions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  portfolio_id uuid not null references public.portfolios(id),
  position_id uuid not null references public.positions(id),
  portfolio_kind public.portfolio_kind not null,
  paper_order_id uuid references public.paper_orders(id),
  instrument_id uuid not null references public.instruments(id),
  side public.order_side not null,
  event_type public.execution_event_type not null default 'EXECUTION',
  effect_multiplier smallint not null default 1 check (effect_multiplier in (-1, 1)),
  reverses_execution_id uuid references public.position_executions(id),
  executed_at timestamptz not null,
  unit_price numeric(24,6) not null check (unit_price > 0),
  quantity bigint not null check (quantity > 0),
  fee numeric(24,6) not null default 0 check (fee >= 0),
  tax numeric(24,6) not null default 0 check (tax >= 0),
  note text,
  source_signal_id uuid references public.signals(id),
  source_position_signal_id uuid references public.position_signals(id),
  strategy_version_id uuid references public.strategy_versions(id),
  idempotency_key text not null,
  recorded_at timestamptz not null default now(),
  unique (portfolio_id, idempotency_key),
  check ((portfolio_kind = 'MANUAL_LIVE' and paper_order_id is null) or (portfolio_kind in ('SANDBOX_PAPER', 'RANKED_PAPER') and paper_order_id is not null)),
  check ((event_type = 'REVERSAL' and effect_multiplier = -1) or (event_type <> 'REVERSAL' and effect_multiplier = 1)),
  check ((event_type = 'EXECUTION' and reverses_execution_id is null) or (event_type in ('REVERSAL', 'CORRECTION') and reverses_execution_id is not null))
);

create unique index position_execution_one_reversal_uidx
  on public.position_executions(reverses_execution_id)
  where event_type = 'REVERSAL';
create unique index position_executions_one_fill_per_order_uidx
  on public.position_executions(paper_order_id)
  where paper_order_id is not null and event_type = 'EXECUTION';
create index position_executions_replay_idx
  on public.position_executions(position_id, executed_at, recorded_at, id);

create table public.portfolio_cash_ledger (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  portfolio_id uuid not null references public.portfolios(id),
  portfolio_kind public.portfolio_kind not null,
  sequence_no bigint not null check (sequence_no > 0),
  event_type public.cash_event_type not null,
  amount numeric(24,6) not null check (amount <> 0),
  resulting_balance numeric(24,6) not null check (resulting_balance >= 0),
  position_execution_id uuid references public.position_executions(id),
  reverses_ledger_id uuid references public.portfolio_cash_ledger(id),
  idempotency_key text not null,
  occurred_at timestamptz not null,
  recorded_at timestamptz not null default now(),
  unique (portfolio_id, sequence_no),
  unique (portfolio_id, idempotency_key),
  check ((event_type in ('REVERSAL', 'CORRECTION') and reverses_ledger_id is not null) or (event_type not in ('REVERSAL', 'CORRECTION') and reverses_ledger_id is null))
);

create index portfolio_cash_replay_idx
  on public.portfolio_cash_ledger(portfolio_id, sequence_no);

create table public.corporate_actions (
  id uuid primary key default gen_random_uuid(),
  instrument_id uuid not null references public.instruments(id),
  action_type public.corporate_action_type not null,
  ex_date date not null,
  record_date date,
  payable_date date,
  ratio_numerator numeric(24,10),
  ratio_denominator numeric(24,10),
  cash_amount numeric(24,6),
  source text not null,
  source_revision text,
  created_at timestamptz not null default now(),
  unique (instrument_id, action_type, ex_date, source),
  check (ratio_numerator is null or ratio_numerator > 0),
  check (ratio_denominator is null or ratio_denominator > 0),
  check (cash_amount is null or cash_amount >= 0)
);

create table public.position_adjustments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  position_id uuid not null references public.positions(id),
  corporate_action_id uuid references public.corporate_actions(id),
  adjustment_type public.adjustment_type not null,
  quantity_delta numeric(24,6) not null default 0,
  cost_basis_delta numeric(24,6) not null default 0,
  cash_delta numeric(24,6) not null default 0,
  payload jsonb not null default '{}'::jsonb,
  idempotency_key text not null,
  occurred_at timestamptz not null,
  recorded_at timestamptz not null default now(),
  unique (position_id, idempotency_key),
  check (jsonb_typeof(payload) = 'object')
);

create table public.portfolio_nav_snapshots (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  portfolio_id uuid not null references public.portfolios(id),
  ranking_track_id uuid,
  valuation_at timestamptz not null,
  cash numeric(24,6) not null check (cash >= 0),
  market_value numeric(24,6) not null check (market_value >= 0),
  nav numeric(24,6) not null check (nav >= 0),
  realized_pnl numeric(24,6) not null,
  unrealized_pnl numeric(24,6) not null,
  fees numeric(24,6) not null default 0 check (fees >= 0),
  taxes numeric(24,6) not null default 0 check (taxes >= 0),
  data_version text not null,
  created_at timestamptz not null default now(),
  unique (portfolio_id, valuation_at),
  check (nav = cash + market_value)
);

create table public.alert_settings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  strategy_id uuid references public.strategies(id) on delete cascade,
  instrument_id uuid references public.instruments(id),
  enabled boolean not null default true,
  buy_enabled boolean not null default true,
  sell_enabled boolean not null default true,
  cooldown_minutes integer not null default 0 check (cooldown_minutes >= 0),
  quiet_start time,
  quiet_end time,
  timezone text not null default 'Asia/Seoul',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.device_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  token_hash text not null,
  encrypted_token text not null,
  platform text not null check (platform in ('ios', 'android')),
  enabled boolean not null default true,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (user_id, token_hash)
);

create table public.push_outbox (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  signal_id uuid references public.signals(id),
  position_signal_id uuid references public.position_signals(id),
  position_id uuid references public.positions(id),
  dedupe_key text not null unique,
  status public.outbox_status not null default 'PENDING',
  redacted_payload jsonb not null,
  available_at timestamptz not null default now(),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  last_error_code text,
  sent_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((signal_id is not null)::integer + (position_signal_id is not null)::integer = 1),
  check (jsonb_typeof(redacted_payload) = 'object')
);

create index push_outbox_dispatch_idx
  on public.push_outbox(status, available_at) where status in ('PENDING', 'FAILED');

create table public.backtest_runs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  strategy_version_id uuid references public.strategy_versions(id),
  universe_version_id uuid not null references public.universe_versions(id),
  status public.backtest_status not null default 'QUEUED',
  period_start date not null,
  period_end date not null,
  train_range daterange,
  validation_range daterange,
  holdout_range daterange,
  horizons integer[] not null default array[5,20,60],
  dataset_version text not null,
  indicator_version_set jsonb not null,
  engine_version text not null,
  formula_version text not null,
  cost_model_version_id uuid not null references public.cost_model_versions(id),
  fill_model_version_id uuid not null references public.paper_fill_model_versions(id),
  seed bigint not null,
  config jsonb not null,
  runtime_ms bigint check (runtime_ms is null or runtime_ms >= 0),
  peak_memory_bytes bigint check (peak_memory_bytes is null or peak_memory_bytes >= 0),
  estimated_cost numeric(24,6) check (estimated_cost is null or estimated_cost >= 0),
  created_at timestamptz not null default now(),
  started_at timestamptz,
  completed_at timestamptz,
  check (period_end >= period_start),
  check (array_length(horizons, 1) > 0),
  check (jsonb_typeof(indicator_version_set) = 'object'),
  check (jsonb_typeof(config) = 'object')
);

create table public.backtest_metrics (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  backtest_run_id uuid not null references public.backtest_runs(id) on delete cascade,
  horizon_sessions integer not null check (horizon_sessions > 0),
  sample_count integer not null check (sample_count >= 0),
  instrument_count integer not null check (instrument_count >= 0),
  net_excess_return numeric(18,10),
  hit_rate numeric(18,10),
  max_drawdown numeric(18,10),
  downside_risk numeric(18,10),
  stability_score numeric(18,10),
  confidence_low numeric(18,10),
  confidence_high numeric(18,10),
  insufficient_data boolean not null,
  created_at timestamptz not null default now(),
  unique (backtest_run_id, horizon_sessions),
  check (hit_rate is null or (hit_rate between 0 and 1)),
  check (max_drawdown is null or (max_drawdown between -1 and 0)),
  check (confidence_low is null or confidence_high is null or confidence_low <= confidence_high),
  check (insufficient_data = (sample_count < 30))
);

create table public.ranking_snapshots (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  kind public.ranking_snapshot_kind not null,
  period public.ranking_period not null,
  as_of timestamptz not null,
  formula_version text not null,
  dataset_version text not null,
  engine_version text not null,
  cost_model_version_id uuid not null references public.cost_model_versions(id),
  fill_model_version_id uuid not null references public.paper_fill_model_versions(id),
  rows jsonb not null,
  is_published boolean not null default false,
  created_at timestamptz not null default now(),
  unique (kind, period, as_of, formula_version),
  check (jsonb_typeof(rows) = 'array')
);

create table public.ranking_tracks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  portfolio_id uuid not null unique references public.portfolios(id),
  strategy_version_id uuid not null references public.strategy_versions(id),
  universe_version_id uuid not null references public.universe_versions(id),
  sell_rule_version_id uuid not null references public.sell_rule_versions(id),
  fill_model_version_id uuid not null references public.paper_fill_model_versions(id),
  cost_model_version_id uuid not null references public.cost_model_versions(id),
  status public.ranking_track_status not null default 'ACTIVE',
  initial_capital numeric(24,6) not null check (initial_capital > 0),
  max_position_weight numeric(18,10) not null default 0.1 check (max_position_weight > 0 and max_position_weight <= 0.10),
  max_open_positions smallint not null default 10 check (max_open_positions between 1 and 10),
  priority_formula_version text not null,
  restart_cooldown_days integer not null default 30 check (restart_cooldown_days >= 0),
  cumulative_return numeric(18,10),
  max_drawdown numeric(18,10),
  trade_count integer not null default 0 check (trade_count >= 0),
  is_public boolean not null default false,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  last_nav_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((status = 'ACTIVE' and ended_at is null) or (status <> 'ACTIVE' and ended_at is not null)),
  check (max_drawdown is null or max_drawdown between -1 and 0)
);

create unique index ranking_tracks_one_active_per_user_uidx
  on public.ranking_tracks(user_id) where status = 'ACTIVE';
create index ranking_tracks_public_rank_idx
  on public.ranking_tracks(cumulative_return desc, max_drawdown, started_at)
  where is_public and status in ('ACTIVE', 'ENDED');

alter table public.portfolio_nav_snapshots
  add constraint portfolio_nav_snapshots_ranking_track_fk
  foreign key (ranking_track_id) references public.ranking_tracks(id);

create table public.ranking_track_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  ranking_track_id uuid not null references public.ranking_tracks(id),
  event_type public.ranking_track_event_type not null,
  payload jsonb not null default '{}'::jsonb,
  idempotency_key text not null,
  occurred_at timestamptz not null default now(),
  unique (ranking_track_id, idempotency_key),
  check (jsonb_typeof(payload) = 'object')
);

create table public.indicator_tier_snapshots (
  id uuid primary key default gen_random_uuid(),
  indicator_definition_id uuid not null references public.indicator_definitions(id),
  universe_version_id uuid not null references public.universe_versions(id),
  period public.ranking_period not null check (period in ('M3', 'M6', 'Y1')),
  tier text not null check (tier in ('S', 'A', 'B', 'C', 'INSUFFICIENT_DATA')),
  ablation_score numeric(18,10),
  stability_score numeric(18,10),
  deduplicated_frequency_score numeric(18,10),
  total_score numeric(18,10),
  sample_count integer not null check (sample_count >= 0),
  formula_version text not null,
  dataset_version text not null,
  is_published boolean not null default false,
  as_of timestamptz not null,
  created_at timestamptz not null default now(),
  unique (indicator_definition_id, universe_version_id, period, as_of, formula_version),
  check ((tier = 'INSUFFICIENT_DATA') = (sample_count < 30))
);

create table public.plans (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  name_ko text not null,
  is_active boolean not null default true,
  is_paid boolean not null default false,
  created_at timestamptz not null default now()
);

create table public.features (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  name_ko text not null,
  description_ko text not null,
  created_at timestamptz not null default now()
);

create table public.plan_entitlements (
  plan_id uuid not null references public.plans(id) on delete cascade,
  feature_id uuid not null references public.features(id) on delete cascade,
  enabled boolean not null default true,
  limit_value numeric(24,6),
  configuration jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  primary key (plan_id, feature_id),
  check (limit_value is null or limit_value >= 0),
  check (jsonb_typeof(configuration) = 'object')
);

create table public.user_entitlements (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  feature_id uuid not null references public.features(id) on delete cascade,
  enabled boolean not null,
  limit_value numeric(24,6),
  source public.entitlement_source not null,
  starts_at timestamptz not null default now(),
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, feature_id, starts_at),
  check (limit_value is null or limit_value >= 0),
  check (expires_at is null or expires_at > starts_at)
);

create table public.feature_flags (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  enabled boolean not null default false,
  configuration jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  check (jsonb_typeof(configuration) = 'object')
);

create table public.usage_counters (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  feature_id uuid not null references public.features(id) on delete cascade,
  period_start timestamptz not null,
  period_end timestamptz not null,
  count numeric(24,6) not null default 0 check (count >= 0),
  updated_at timestamptz not null default now(),
  unique (user_id, feature_id, period_start, period_end),
  check (period_end > period_start)
);

create table public.community_reports (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  target_public_profile_id uuid references public.profiles(public_profile_id) on delete set null,
  target_strategy_id uuid references public.strategies(id) on delete set null,
  reason_code text not null,
  details text,
  status public.report_status not null default 'OPEN',
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

create table public.community_blocks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  blocked_public_profile_id uuid not null references public.profiles(public_profile_id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (user_id, blocked_public_profile_id)
);

create table public.audit_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  actor_user_id uuid references auth.users(id) on delete set null,
  action text not null,
  entity_type text not null,
  entity_id uuid,
  before_redacted jsonb,
  after_redacted jsonb,
  request_id text,
  ip_hash text,
  occurred_at timestamptz not null default now(),
  check (before_redacted is null or jsonb_typeof(before_redacted) = 'object'),
  check (after_redacted is null or jsonb_typeof(after_redacted) = 'object')
);

create index candles_scan_idx on public.candles(instrument_id, timeframe, close_at desc) where is_final and not is_stale;
create index universe_memberships_point_in_time_idx on public.universe_memberships(universe_version_id, effective_from, effective_to, instrument_id);
create index strategy_versions_user_idx on public.strategy_versions(user_id, strategy_id, version desc);
create index signals_feed_idx on public.signals(user_id, created_at desc);
create index positions_user_status_idx on public.positions(user_id, portfolio_kind, status, updated_at desc);
create index nav_snapshots_track_idx on public.portfolio_nav_snapshots(ranking_track_id, valuation_at) where ranking_track_id is not null;
create index ranking_track_events_replay_idx on public.ranking_track_events(ranking_track_id, occurred_at, id);
create index audit_logs_user_idx on public.audit_logs(user_id, occurred_at desc);

-- Trigger functions
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create or replace function public.deny_update_delete()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if tg_op = 'DELETE' and coalesce(current_setting('app.account_purge', true), 'off') = 'on' then
    return old;
  end if;
  raise exception '% is append-only; add reversal/correction event', tg_table_name
    using errcode = '55000';
end;
$$;

create or replace function public.guard_finalized_version()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  child_count integer;
begin
  if tg_op = 'DELETE' and coalesce(current_setting('app.account_purge', true), 'off') = 'on' then
    return old;
  end if;
  if tg_op = 'INSERT' then
    if new.finalized_at is not null then
      raise exception 'version must be inserted as draft then finalized' using errcode = '23514';
    end if;
    return new;
  end if;
  if old.finalized_at is not null then
    raise exception 'finalized % row cannot change; create new version', tg_table_name
      using errcode = '55000';
  end if;
  if tg_op = 'DELETE' then
    return old;
  end if;
  if old.finalized_at is null and new.finalized_at is not null then
    if tg_table_name = 'strategy_versions' then
      select count(*) into child_count from public.strategy_rules where strategy_version_id = new.id;
      if child_count < 1 or child_count > 5 then
        raise exception 'strategy version must contain 1..5 rules before finalization' using errcode = '23514';
      end if;
    elsif tg_table_name = 'universe_versions' then
      select count(*) into child_count from public.universe_memberships where universe_version_id = new.id;
      if child_count < 1 then
        raise exception 'universe version must contain a membership before finalization' using errcode = '23514';
      end if;
    end if;
  end if;
  return new;
end;
$$;

create or replace function public.validate_universe_version_owner()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  definition_owner uuid;
begin
  select user_id into definition_owner
  from public.universe_definitions where id = new.universe_definition_id;
  if not found or definition_owner is distinct from new.user_id then
    raise exception 'universe version owner mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.validate_strategy_version_owner()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  strategy_owner uuid;
  universe_owner uuid;
  universe_final timestamptz;
begin
  select user_id into strategy_owner from public.strategies where id = new.strategy_id;
  select user_id, finalized_at into universe_owner, universe_final
  from public.universe_versions where id = new.universe_version_id;
  if not found or strategy_owner is distinct from new.user_id then
    raise exception 'strategy version owner mismatch' using errcode = '23514';
  end if;
  if universe_final is null or (universe_owner is not null and universe_owner is distinct from new.user_id) then
    raise exception 'strategy requires finalized visible universe version' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.validate_sell_rule_version_owner()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  set_owner uuid;
begin
  select user_id into set_owner from public.sell_rule_sets where id = new.sell_rule_set_id;
  if not found or set_owner is distinct from new.user_id then
    raise exception 'sell rule version owner mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.guard_profile_identity()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if row(new.user_id, new.public_profile_id, new.created_at)
     is distinct from row(old.user_id, old.public_profile_id, old.created_at) then
    raise exception 'profile identity fields are immutable' using errcode = '55000';
  end if;
  return new;
end;
$$;

create or replace function public.guard_universe_membership()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  target_version uuid;
  owner_id uuid;
  locked_at timestamptz;
begin
  target_version := case when tg_op = 'DELETE' then old.universe_version_id else new.universe_version_id end;
  if tg_op = 'DELETE' and coalesce(current_setting('app.account_purge', true), 'off') = 'on' then
    return old;
  end if;
  select user_id, finalized_at into owner_id, locked_at from public.universe_versions where id = target_version;
  if locked_at is not null then
    raise exception 'finalized universe membership cannot change' using errcode = '55000';
  end if;
  if tg_op <> 'DELETE' and new.user_id is distinct from owner_id then
    raise exception 'universe membership owner mismatch' using errcode = '23514';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create or replace function public.guard_strategy_rule()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  target_version uuid;
  owner_id uuid;
  locked_at timestamptz;
begin
  target_version := case when tg_op = 'DELETE' then old.strategy_version_id else new.strategy_version_id end;
  if tg_op = 'DELETE' and coalesce(current_setting('app.account_purge', true), 'off') = 'on' then
    return old;
  end if;
  select user_id, finalized_at into owner_id, locked_at from public.strategy_versions where id = target_version;
  if locked_at is not null then
    raise exception 'finalized strategy rules cannot change' using errcode = '55000';
  end if;
  if tg_op <> 'DELETE' and new.user_id is distinct from owner_id then
    raise exception 'strategy rule owner mismatch' using errcode = '23514';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create or replace function public.validate_position_portfolio()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  p_user uuid;
  p_kind public.portfolio_kind;
begin
  select user_id, kind into p_user, p_kind from public.portfolios where id = new.portfolio_id;
  if not found or p_user is distinct from new.user_id or p_kind is distinct from new.portfolio_kind then
    raise exception 'position portfolio owner/kind mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.validate_paper_order()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  p_user uuid;
  p_kind public.portfolio_kind;
begin
  select user_id, kind into p_user, p_kind from public.portfolios where id = new.portfolio_id;
  if not found or p_user is distinct from new.user_id or p_kind is distinct from new.portfolio_kind then
    raise exception 'paper order portfolio owner/kind mismatch' using errcode = '23514';
  end if;
  if p_kind = 'MANUAL_LIVE' then
    raise exception 'manual portfolio cannot contain paper order' using errcode = '23514';
  end if;
  if new.side = 'SELL' and new.position_id is null then
    raise exception 'sell paper order requires position' using errcode = '23514';
  end if;
  if new.position_id is not null and not exists (
    select 1 from public.positions p
    where p.id = new.position_id and p.portfolio_id = new.portfolio_id
      and p.user_id = new.user_id and p.instrument_id = new.instrument_id
  ) then
    raise exception 'paper order position mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.guard_paper_order_update()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if row(new.user_id, new.portfolio_id, new.portfolio_kind, new.position_id, new.instrument_id,
         new.side, new.quantity, new.scheduled_market_session_id, new.source_signal_id,
         new.source_position_signal_id, new.fill_model_version_id, new.cost_model_version_id,
         new.can_user_cancel, new.idempotency_key, new.submitted_at)
     is distinct from
     row(old.user_id, old.portfolio_id, old.portfolio_kind, old.position_id, old.instrument_id,
         old.side, old.quantity, old.scheduled_market_session_id, old.source_signal_id,
         old.source_position_signal_id, old.fill_model_version_id, old.cost_model_version_id,
         old.can_user_cancel, old.idempotency_key, old.submitted_at) then
    raise exception 'paper order terms are immutable' using errcode = '55000';
  end if;
  if new.status is distinct from old.status then
    if old.status <> 'PENDING' then
      raise exception 'terminal paper order status cannot change' using errcode = '55000';
    end if;
    if new.status = 'FILLED' and pg_trigger_depth() < 2 then
      raise exception 'paper order becomes filled only through execution' using errcode = '55000';
    end if;
    if new.status = 'CANCELLED' and (old.portfolio_kind = 'RANKED_PAPER' or not old.can_user_cancel) then
      raise exception 'paper order cannot be cancelled' using errcode = '55000';
    end if;
  end if;
  return new;
end;
$$;

create or replace function public.validate_position_execution()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  p_user uuid;
  p_kind public.portfolio_kind;
  order_row public.paper_orders%rowtype;
  position_row public.positions%rowtype;
  target_row public.position_executions%rowtype;
begin
  select user_id, kind into p_user, p_kind from public.portfolios where id = new.portfolio_id;
  if not found or p_user is distinct from new.user_id or p_kind is distinct from new.portfolio_kind then
    raise exception 'execution portfolio owner/kind mismatch' using errcode = '23514';
  end if;
  select * into position_row
  from public.positions p where p.id = new.position_id
    and p.portfolio_id = new.portfolio_id and p.user_id = new.user_id
    and p.instrument_id = new.instrument_id and p.portfolio_kind = new.portfolio_kind
  for update;
  if not found then
    raise exception 'execution position mismatch' using errcode = '23514';
  end if;
  if new.event_type = 'EXECUTION' and new.side = 'BUY'
     and position_row.status not in ('OPEN', 'PARTIALLY_CLOSED') then
    raise exception 'buy execution position status mismatch' using errcode = '23514';
  end if;
  if new.event_type = 'EXECUTION' and new.side = 'SELL'
     and (position_row.status not in ('OPEN', 'EXIT_PENDING', 'PARTIALLY_CLOSED')
          or new.quantity > position_row.quantity) then
    raise exception 'sell execution position status/quantity mismatch' using errcode = '23514';
  end if;
  if p_kind in ('SANDBOX_PAPER', 'RANKED_PAPER') then
    select * into order_row from public.paper_orders where id = new.paper_order_id for update;
    if not found or order_row.portfolio_id <> new.portfolio_id or order_row.user_id <> new.user_id
       or order_row.instrument_id <> new.instrument_id or order_row.side <> new.side
       or order_row.portfolio_kind <> new.portfolio_kind
       or (order_row.position_id is not null and order_row.position_id <> new.position_id) then
      raise exception 'paper execution must match paper order' using errcode = '23514';
    end if;
    if new.event_type = 'EXECUTION'
       and (order_row.status <> 'PENDING' or order_row.quantity <> new.quantity) then
      raise exception 'paper fill requires pending order and full order quantity' using errcode = '23514';
    end if;
    if new.event_type in ('REVERSAL', 'CORRECTION') and order_row.status <> 'FILLED' then
      raise exception 'paper correction requires filled order' using errcode = '23514';
    end if;
  elsif new.paper_order_id is not null then
    raise exception 'manual execution cannot reference paper order' using errcode = '23514';
  end if;
  if p_kind = 'RANKED_PAPER' and new.event_type = 'EXECUTION' and new.side = 'BUY'
     and exists (
       select 1 from public.position_executions e
       where e.position_id = new.position_id and e.side = 'BUY' and e.event_type = 'EXECUTION'
     ) then
    raise exception 'ranked paper position cannot add buy' using errcode = '23514';
  end if;
  if p_kind = 'RANKED_PAPER' and new.event_type = 'EXECUTION' and new.side = 'SELL'
     and new.quantity <> position_row.quantity then
    raise exception 'ranked paper sell must close full remaining quantity' using errcode = '23514';
  end if;
  if new.reverses_execution_id is not null then
    select * into target_row from public.position_executions where id = new.reverses_execution_id;
    if not found or target_row.portfolio_id <> new.portfolio_id or target_row.position_id <> new.position_id
       or target_row.instrument_id <> new.instrument_id or target_row.side <> new.side
       or target_row.paper_order_id is distinct from new.paper_order_id then
      raise exception 'execution correction target mismatch' using errcode = '23514';
    end if;
  end if;
  return new;
end;
$$;

create or replace function public.finalize_paper_order_fill()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if new.event_type = 'EXECUTION' and new.paper_order_id is not null then
    update public.paper_orders
       set status = 'FILLED', updated_at = now()
     where id = new.paper_order_id and status = 'PENDING';
    if not found then
      raise exception 'paper order fill transition failed' using errcode = '23514';
    end if;
  end if;
  return new;
end;
$$;

create or replace function public.validate_cash_ledger()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  p_user uuid;
  p_kind public.portfolio_kind;
  p_initial numeric(24,6);
  prior_balance numeric(24,6);
  prior_sequence bigint;
begin
  select user_id, kind, initial_cash into p_user, p_kind, p_initial
  from public.portfolios where id = new.portfolio_id for update;
  if not found or p_user is distinct from new.user_id or p_kind is distinct from new.portfolio_kind then
    raise exception 'cash ledger portfolio owner/kind mismatch' using errcode = '23514';
  end if;
  select coalesce(max(sequence_no), 0) into prior_sequence
  from public.portfolio_cash_ledger where portfolio_id = new.portfolio_id;
  select resulting_balance into prior_balance
  from public.portfolio_cash_ledger
  where portfolio_id = new.portfolio_id
  order by sequence_no desc limit 1;
  prior_balance := coalesce(prior_balance, 0);
  if new.sequence_no <> prior_sequence + 1 then
    raise exception 'cash ledger sequence must be %', prior_sequence + 1 using errcode = '23514';
  end if;
  if new.resulting_balance <> prior_balance + new.amount then
    raise exception 'cash ledger resulting balance mismatch' using errcode = '23514';
  end if;
  if prior_sequence = 0 and (new.event_type <> 'INITIAL_CAPITAL' or new.amount <> p_initial) then
    raise exception 'first cash ledger row must load portfolio initial cash' using errcode = '23514';
  end if;
  if prior_sequence > 0 and new.event_type = 'INITIAL_CAPITAL' then
    raise exception 'initial capital can be recorded once' using errcode = '23514';
  end if;
  if p_kind = 'RANKED_PAPER' and prior_sequence > 0 and new.event_type in ('DEPOSIT', 'WITHDRAWAL') then
    raise exception 'ranked portfolio does not allow cash flows' using errcode = '23514';
  end if;
  if new.position_execution_id is not null and not exists (
    select 1 from public.position_executions e
    where e.id = new.position_execution_id and e.portfolio_id = new.portfolio_id
      and e.user_id = new.user_id and e.portfolio_kind = new.portfolio_kind
  ) then
    raise exception 'cash ledger execution mismatch' using errcode = '23514';
  end if;
  if new.reverses_ledger_id is not null and not exists (
    select 1 from public.portfolio_cash_ledger l
    where l.id = new.reverses_ledger_id and l.portfolio_id = new.portfolio_id
  ) then
    raise exception 'cash ledger correction target mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.apply_cash_ledger_cache()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  perform set_config('app.cash_ledger_write', 'on', true);
  update public.portfolios set cash_balance = new.resulting_balance, updated_at = now() where id = new.portfolio_id;
  perform set_config('app.cash_ledger_write', 'off', true);
  return new;
end;
$$;

create or replace function public.guard_portfolio_cash_cache()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if tg_op = 'INSERT' then
    if new.cash_balance <> 0 then
      raise exception 'portfolio cash cache starts at zero; add initial ledger row' using errcode = '23514';
    end if;
    return new;
  end if;
  if new.cash_balance is distinct from old.cash_balance
     and coalesce(current_setting('app.cash_ledger_write', true), 'off') <> 'on' then
    raise exception 'cash balance cache changes only through cash ledger' using errcode = '55000';
  end if;
  return new;
end;
$$;

-- Atomic paper fill boundary. Worker supplies final fill price. Cost rows stay
-- versioned and immutable; this function calculates fee/tax from order model.
-- First BUY uses a reserved position row: order quantity, zero average cost,
-- and no prior execution. Later fills require position cache quantity to match
-- append-only execution replay.
create or replace function public.apply_paper_fill(
  target_order_id uuid,
  target_unit_price numeric,
  target_executed_at timestamptz,
  target_idempotency_key text
)
returns table (
  execution_id uuid,
  cash_ledger_id uuid,
  cash_balance numeric,
  position_quantity bigint,
  position_average_cost numeric,
  position_status public.position_status,
  replayed boolean
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  order_row public.paper_orders%rowtype;
  portfolio_row public.portfolios%rowtype;
  position_row public.positions%rowtype;
  cost_row public.cost_model_versions%rowtype;
  existing_execution public.position_executions%rowtype;
  existing_ledger public.portfolio_cash_ledger%rowtype;
  new_execution_id uuid := gen_random_uuid();
  new_ledger_id uuid := gen_random_uuid();
  prior_sequence bigint;
  prior_balance numeric(24,6);
  replay_quantity bigint;
  execution_count bigint;
  base_quantity bigint;
  next_quantity bigint;
  gross_amount numeric(24,6);
  fill_fee numeric(24,6);
  fill_tax numeric(24,6);
  cash_delta numeric(24,6);
  next_balance numeric(24,6);
  next_average_cost numeric(24,6);
  next_realized_pnl numeric(24,6);
  next_status public.position_status;
  next_closed_at timestamptz;
begin
  if target_unit_price is null or target_unit_price <= 0 then
    raise exception 'paper fill unit price must be positive' using errcode = '22023';
  end if;
  if target_executed_at is null then
    raise exception 'paper fill execution time is required' using errcode = '22023';
  end if;
  if target_idempotency_key is null or btrim(target_idempotency_key) = ''
     or char_length(target_idempotency_key) > 200 then
    raise exception 'paper fill idempotency key must contain 1..200 characters' using errcode = '22023';
  end if;

  select * into order_row
  from public.paper_orders po
  where po.id = target_order_id
  for update;
  if not found then
    raise exception 'paper order not found' using errcode = 'P0002';
  end if;
  if order_row.position_id is null then
    raise exception 'atomic paper fill requires position-bound order' using errcode = '23514';
  end if;

  select * into portfolio_row
  from public.portfolios p
  where p.id = order_row.portfolio_id
  for update;
  if not found or portfolio_row.user_id is distinct from order_row.user_id
     or portfolio_row.kind is distinct from order_row.portfolio_kind then
    raise exception 'paper fill portfolio mismatch' using errcode = '23514';
  end if;

  select * into position_row
  from public.positions p
  where p.id = order_row.position_id
    and p.portfolio_id = order_row.portfolio_id
    and p.user_id = order_row.user_id
    and p.instrument_id = order_row.instrument_id
    and p.portfolio_kind = order_row.portfolio_kind
  for update;
  if not found then
    raise exception 'paper fill position mismatch' using errcode = '23514';
  end if;

  select * into cost_row
  from public.cost_model_versions c
  where c.id = order_row.cost_model_version_id;
  if not found then
    raise exception 'paper fill cost model missing' using errcode = '23514';
  end if;

  gross_amount := target_unit_price * order_row.quantity;
  fill_fee := ceil(gross_amount * case
    when order_row.side = 'BUY' then cost_row.buy_fee_rate
    else cost_row.sell_fee_rate
  end);
  fill_tax := case when order_row.side = 'SELL'
    then ceil(gross_amount * cost_row.sell_tax_rate)
    else 0
  end;

  select * into existing_execution
  from public.position_executions e
  where e.portfolio_id = order_row.portfolio_id
    and e.idempotency_key = target_idempotency_key;
  if found then
    if existing_execution.paper_order_id is distinct from order_row.id
       or existing_execution.position_id is distinct from order_row.position_id
       or existing_execution.event_type <> 'EXECUTION'
       or existing_execution.side is distinct from order_row.side
       or existing_execution.quantity is distinct from order_row.quantity
       or existing_execution.unit_price is distinct from target_unit_price
       or existing_execution.fee is distinct from fill_fee
       or existing_execution.tax is distinct from fill_tax
       or existing_execution.executed_at is distinct from target_executed_at then
      raise exception 'paper fill idempotency conflict' using errcode = '23505';
    end if;
    select * into existing_ledger
    from public.portfolio_cash_ledger l
    where l.position_execution_id = existing_execution.id;
    if not found then
      raise exception 'paper fill replay found incomplete ledger' using errcode = '23514';
    end if;
    execution_id := existing_execution.id;
    cash_ledger_id := existing_ledger.id;
    cash_balance := existing_ledger.resulting_balance;
    position_quantity := position_row.quantity;
    position_average_cost := position_row.average_cost;
    position_status := position_row.status;
    replayed := true;
    return next;
    return;
  end if;

  if order_row.status <> 'PENDING' then
    raise exception 'paper fill requires pending order' using errcode = '55000';
  end if;
  if exists (
    select 1 from public.position_executions e
    where e.paper_order_id = order_row.id and e.event_type = 'EXECUTION'
  ) then
    raise exception 'paper order already has execution with another key' using errcode = '23505';
  end if;

  select l.sequence_no, l.resulting_balance
    into prior_sequence, prior_balance
  from public.portfolio_cash_ledger l
  where l.portfolio_id = portfolio_row.id
  order by l.sequence_no desc
  limit 1;
  if not found then
    raise exception 'paper portfolio requires initial cash ledger' using errcode = '23514';
  end if;
  if prior_balance is distinct from portfolio_row.cash_balance then
    raise exception 'paper portfolio cash cache mismatch' using errcode = '23514';
  end if;

  select count(*), coalesce(sum(
    case when e.side = 'BUY' then e.quantity * e.effect_multiplier
         else -e.quantity * e.effect_multiplier end
  ), 0)::bigint
    into execution_count, replay_quantity
  from public.position_executions e
  where e.position_id = position_row.id;

  if execution_count = 0 then
    if order_row.side <> 'BUY' or position_row.quantity <> order_row.quantity
       or position_row.average_cost <> 0 or position_row.realized_pnl <> 0 then
      raise exception 'first buy requires reserved zero-cost position matching order quantity'
        using errcode = '23514';
    end if;
    base_quantity := 0;
  else
    if replay_quantity <> position_row.quantity then
      raise exception 'paper position quantity cache mismatch' using errcode = '23514';
    end if;
    base_quantity := position_row.quantity;
  end if;

  if order_row.side = 'BUY' then
    if position_row.status not in ('OPEN', 'PARTIALLY_CLOSED') then
      raise exception 'paper buy position status mismatch' using errcode = '23514';
    end if;
    next_quantity := base_quantity + order_row.quantity;
    next_average_cost := round(
      (base_quantity * position_row.average_cost + gross_amount + fill_fee + fill_tax)
      / next_quantity,
      6
    );
    next_realized_pnl := position_row.realized_pnl;
    next_status := 'OPEN';
    next_closed_at := null;
    cash_delta := -(gross_amount + fill_fee + fill_tax);
  else
    if position_row.status not in ('OPEN', 'EXIT_PENDING', 'PARTIALLY_CLOSED')
       or order_row.quantity > base_quantity then
      raise exception 'paper sell exceeds open position quantity' using errcode = '23514';
    end if;
    next_quantity := base_quantity - order_row.quantity;
    next_average_cost := case when next_quantity = 0 then 0 else position_row.average_cost end;
    next_realized_pnl := position_row.realized_pnl
      + gross_amount - fill_fee - fill_tax - (position_row.average_cost * order_row.quantity);
    next_status := case when next_quantity = 0 then 'CLOSED' else 'PARTIALLY_CLOSED' end;
    next_closed_at := case when next_quantity = 0 then target_executed_at else null end;
    cash_delta := gross_amount - fill_fee - fill_tax;
  end if;

  next_balance := prior_balance + cash_delta;
  if next_balance < 0 then
    raise exception 'paper fill has insufficient cash' using errcode = '23514';
  end if;

  insert into public.position_executions
    (id, user_id, portfolio_id, position_id, portfolio_kind, paper_order_id,
     instrument_id, side, event_type, effect_multiplier, executed_at, unit_price,
     quantity, fee, tax, source_signal_id, source_position_signal_id,
     strategy_version_id, idempotency_key)
  values
    (new_execution_id, order_row.user_id, order_row.portfolio_id, position_row.id,
     order_row.portfolio_kind, order_row.id, order_row.instrument_id, order_row.side,
     'EXECUTION', 1, target_executed_at, target_unit_price, order_row.quantity,
     fill_fee, fill_tax, order_row.source_signal_id, order_row.source_position_signal_id,
     position_row.strategy_version_id, target_idempotency_key);

  insert into public.portfolio_cash_ledger
    (id, user_id, portfolio_id, portfolio_kind, sequence_no, event_type, amount,
     resulting_balance, position_execution_id, idempotency_key, occurred_at)
  values
    (new_ledger_id, order_row.user_id, order_row.portfolio_id, order_row.portfolio_kind,
     prior_sequence + 1,
     case when order_row.side = 'BUY' then 'BUY_SETTLEMENT'::public.cash_event_type
          else 'SELL_SETTLEMENT'::public.cash_event_type end,
     cash_delta, next_balance, new_execution_id, target_idempotency_key || ':cash',
     target_executed_at);

  update public.positions
  set quantity = next_quantity,
      average_cost = next_average_cost,
      realized_pnl = next_realized_pnl,
      status = next_status,
      closed_at = next_closed_at,
      updated_at = now()
  where id = position_row.id;

  insert into public.paper_order_events
    (user_id, paper_order_id, event_type, payload, idempotency_key, occurred_at)
  values
    (order_row.user_id, order_row.id, 'FILLED',
     jsonb_build_object('execution_id', new_execution_id, 'cash_ledger_id', new_ledger_id,
       'unit_price', target_unit_price, 'fee', fill_fee, 'tax', fill_tax),
     target_idempotency_key || ':filled', target_executed_at);

  execution_id := new_execution_id;
  cash_ledger_id := new_ledger_id;
  cash_balance := next_balance;
  position_quantity := next_quantity;
  position_average_cost := next_average_cost;
  position_status := next_status;
  replayed := false;
  return next;
end;
$$;

create or replace function public.validate_ranking_track()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  p_user uuid;
  p_kind public.portfolio_kind;
  p_cash numeric(24,6);
  p_initial numeric(24,6);
  strategy_universe uuid;
  strategy_final timestamptz;
  universe_final timestamptz;
  sell_final timestamptz;
  sell_manual boolean;
begin
  select user_id, kind, cash_balance, initial_cash into p_user, p_kind, p_cash, p_initial
  from public.portfolios where id = new.portfolio_id;
  if not found or p_user <> new.user_id or p_kind <> 'RANKED_PAPER' then
    raise exception 'ranking track requires owned RANKED_PAPER portfolio' using errcode = '23514';
  end if;
  select universe_version_id, finalized_at into strategy_universe, strategy_final
  from public.strategy_versions where id = new.strategy_version_id and user_id = new.user_id;
  select finalized_at into universe_final from public.universe_versions where id = new.universe_version_id;
  select finalized_at, manual_only into sell_final, sell_manual
  from public.sell_rule_versions where id = new.sell_rule_version_id and user_id = new.user_id;
  if strategy_universe is null or strategy_universe <> new.universe_version_id
     or strategy_final is null or universe_final is null or sell_final is null then
    raise exception 'ranking track references must be finalized and strategy universe must match' using errcode = '23514';
  end if;
  if sell_manual then
    raise exception 'ranking track requires at least one automatic sell rule' using errcode = '23514';
  end if;
  if tg_op = 'INSERT' and (p_cash <> new.initial_capital or p_initial <> new.initial_capital) then
    raise exception 'ranking portfolio cash must equal initial capital at start' using errcode = '23514';
  end if;
  if tg_op = 'UPDATE' and old.status <> 'ACTIVE' and new.status = 'ACTIVE' then
    raise exception 'ended ranking track cannot reactivate; create new track' using errcode = '55000';
  end if;
  if tg_op = 'UPDATE' and row(new.portfolio_id, new.strategy_version_id, new.universe_version_id,
      new.sell_rule_version_id, new.fill_model_version_id, new.cost_model_version_id,
      new.initial_capital, new.max_position_weight, new.max_open_positions,
      new.priority_formula_version, new.restart_cooldown_days, new.started_at)
    is distinct from row(old.portfolio_id, old.strategy_version_id, old.universe_version_id,
      old.sell_rule_version_id, old.fill_model_version_id, old.cost_model_version_id,
      old.initial_capital, old.max_position_weight, old.max_open_positions,
      old.priority_formula_version, old.restart_cooldown_days, old.started_at) then
    raise exception 'ranking track locked fields cannot change' using errcode = '55000';
  end if;
  return new;
end;
$$;

create or replace function public.validate_nav_snapshot()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if not exists (
    select 1 from public.portfolios p
    where p.id = new.portfolio_id and p.user_id = new.user_id
  ) then
    raise exception 'NAV snapshot portfolio owner mismatch' using errcode = '23514';
  end if;
  if new.ranking_track_id is not null and not exists (
    select 1 from public.ranking_tracks rt
    where rt.id = new.ranking_track_id and rt.portfolio_id = new.portfolio_id
      and rt.user_id = new.user_id
  ) then
    raise exception 'NAV snapshot ranking track mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create or replace function public.close_position_dependents()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if new.status in ('CLOSED', 'ARCHIVED') and old.status not in ('CLOSED', 'ARCHIVED') then
    update public.position_signals
       set status = 'CANCELLED_BY_POSITION_CLOSE', updated_at = now()
     where position_id = new.id and status in ('ACTIVE', 'ACKNOWLEDGED');
    update public.push_outbox
       set status = 'CANCELLED', updated_at = now()
     where position_id = new.id and status in ('PENDING', 'FAILED');
  end if;
  return new;
end;
$$;

create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
begin
  insert into public.profiles(user_id, nickname)
  values (new.id, '사용자-' || replace(left(new.id::text, 18), '-', ''))
  on conflict (user_id) do nothing;
  return new;
end;
$$;

create or replace function public.purge_user_account(target_user_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
begin
  if auth.role() is distinct from 'service_role' then
    raise exception 'service role required' using errcode = '42501';
  end if;
  perform set_config('app.account_purge', 'on', true);
  delete from auth.users where id = target_user_id;
  perform set_config('app.account_purge', 'off', true);
end;
$$;

create or replace function public.validate_community_report()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if new.target_public_profile_id is null and new.target_strategy_id is null then
    raise exception 'community report needs a target' using errcode = '23514';
  end if;
  return new;
end;
$$;

-- Updated-at triggers
create trigger profiles_guard_identity before update on public.profiles for each row execute function public.guard_profile_identity();
create trigger profiles_set_updated_at before update on public.profiles for each row execute function public.set_updated_at();
create trigger instruments_set_updated_at before update on public.instruments for each row execute function public.set_updated_at();
create trigger universe_definitions_set_updated_at before update on public.universe_definitions for each row execute function public.set_updated_at();
create trigger strategies_set_updated_at before update on public.strategies for each row execute function public.set_updated_at();
create trigger portfolios_set_updated_at before update on public.portfolios for each row execute function public.set_updated_at();
create trigger sell_rule_sets_set_updated_at before update on public.sell_rule_sets for each row execute function public.set_updated_at();
create trigger positions_set_updated_at before update on public.positions for each row execute function public.set_updated_at();
create trigger position_signals_set_updated_at before update on public.position_signals for each row execute function public.set_updated_at();
create trigger paper_orders_set_updated_at before update on public.paper_orders for each row execute function public.set_updated_at();
create trigger alert_settings_set_updated_at before update on public.alert_settings for each row execute function public.set_updated_at();
create trigger push_outbox_set_updated_at before update on public.push_outbox for each row execute function public.set_updated_at();
create trigger ranking_tracks_set_updated_at before update on public.ranking_tracks for each row execute function public.set_updated_at();
create trigger feature_flags_set_updated_at before update on public.feature_flags for each row execute function public.set_updated_at();
create trigger usage_counters_set_updated_at before update on public.usage_counters for each row execute function public.set_updated_at();

-- Immutable version content and children
create trigger universe_versions_guard before insert or update or delete on public.universe_versions for each row execute function public.guard_finalized_version();
create trigger universe_versions_validate_owner before insert or update on public.universe_versions for each row execute function public.validate_universe_version_owner();
create trigger strategy_versions_guard before insert or update or delete on public.strategy_versions for each row execute function public.guard_finalized_version();
create trigger strategy_versions_validate_owner before insert or update on public.strategy_versions for each row execute function public.validate_strategy_version_owner();
create trigger sell_rule_versions_guard before insert or update or delete on public.sell_rule_versions for each row execute function public.guard_finalized_version();
create trigger sell_rule_versions_validate_owner before insert or update on public.sell_rule_versions for each row execute function public.validate_sell_rule_version_owner();
create trigger universe_memberships_guard before insert or update or delete on public.universe_memberships for each row execute function public.guard_universe_membership();
create trigger strategy_rules_guard before insert or update or delete on public.strategy_rules for each row execute function public.guard_strategy_rule();
create trigger paper_fill_models_immutable before update or delete on public.paper_fill_model_versions for each row execute function public.deny_update_delete();
create trigger cost_models_immutable before update or delete on public.cost_model_versions for each row execute function public.deny_update_delete();

-- Ledger and portfolio boundary guards
create trigger positions_validate_portfolio before insert or update of user_id, portfolio_id, portfolio_kind on public.positions for each row execute function public.validate_position_portfolio();
create trigger paper_orders_validate before insert or update of user_id, portfolio_id, portfolio_kind, position_id, instrument_id on public.paper_orders for each row execute function public.validate_paper_order();
create trigger paper_orders_immutable_terms before update on public.paper_orders for each row execute function public.guard_paper_order_update();
create trigger position_executions_validate before insert on public.position_executions for each row execute function public.validate_position_execution();
create trigger position_executions_finalize_order after insert on public.position_executions for each row execute function public.finalize_paper_order_fill();
create trigger cash_ledger_validate before insert on public.portfolio_cash_ledger for each row execute function public.validate_cash_ledger();
create trigger cash_ledger_apply_cache after insert on public.portfolio_cash_ledger for each row execute function public.apply_cash_ledger_cache();
create trigger portfolios_guard_cash_cache before insert or update on public.portfolios for each row execute function public.guard_portfolio_cash_cache();
create trigger ranking_tracks_validate before insert or update on public.ranking_tracks for each row execute function public.validate_ranking_track();
create trigger nav_snapshots_validate before insert or update on public.portfolio_nav_snapshots for each row execute function public.validate_nav_snapshot();
create trigger positions_close_dependents after update of status on public.positions for each row execute function public.close_position_dependents();
create trigger community_reports_validate before insert on public.community_reports for each row execute function public.validate_community_report();

create trigger position_executions_append_only before update or delete on public.position_executions for each row execute function public.deny_update_delete();
create trigger cash_ledger_append_only before update or delete on public.portfolio_cash_ledger for each row execute function public.deny_update_delete();
create trigger paper_order_events_append_only before update or delete on public.paper_order_events for each row execute function public.deny_update_delete();
create trigger position_adjustments_append_only before update or delete on public.position_adjustments for each row execute function public.deny_update_delete();
create trigger ranking_track_events_append_only before update or delete on public.ranking_track_events for each row execute function public.deny_update_delete();
create trigger audit_logs_append_only before update or delete on public.audit_logs for each row execute function public.deny_update_delete();

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users for each row execute function public.handle_new_auth_user();

-- Safe public views. Base user tables remain owner-only through RLS.
create or replace function public.previous_completed_session_close()
returns timestamptz
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  select close_at
  from (
    select distinct close_at
    from public.market_sessions
    where is_trading_day and close_at <= now()
    order by close_at desc
    offset 1 limit 1
  ) prior_session;
$$;

create view public.public_profiles with (security_barrier = true) as
select public_profile_id, nickname::text as nickname, created_at
from public.profiles p
where is_public and deleted_at is null
  and not exists (
    select 1 from public.community_blocks b
    where b.user_id = auth.uid() and b.blocked_public_profile_id = p.public_profile_id
  );

create view public.public_strategy_versions with (security_barrier = true) as
select p.public_profile_id, s.id as strategy_id, s.name as strategy_name,
       sv.id as strategy_version_id, sv.version, sv.universe_version_id,
       sv.timeframe, sv.root_logic, sv.engine_version, sv.created_at
from public.profiles p
join public.strategies s on s.user_id = p.user_id
join public.strategy_versions sv on sv.strategy_id = s.id and sv.user_id = p.user_id
where p.is_public and p.deleted_at is null and s.is_public and sv.finalized_at is not null
  and not exists (
    select 1 from public.community_blocks b
    where b.user_id = auth.uid() and b.blocked_public_profile_id = p.public_profile_id
  );

create view public.public_strategy_rules with (security_barrier = true) as
select p.public_profile_id, sr.strategy_version_id, sr.rule_index, sr.group_key,
       sr.group_logic, i.code as indicator_code, i.version as indicator_version,
       sr.operator, sr.params, sr.compare_value
from public.profiles p
join public.strategies s on s.user_id = p.user_id
join public.strategy_versions sv on sv.strategy_id = s.id and sv.user_id = p.user_id
join public.strategy_rules sr on sr.strategy_version_id = sv.id and sr.user_id = p.user_id
join public.indicator_definitions i on i.id = sr.indicator_definition_id
where p.is_public and p.deleted_at is null and s.is_public and sv.finalized_at is not null
  and not exists (
    select 1 from public.community_blocks b
    where b.user_id = auth.uid() and b.blocked_public_profile_id = p.public_profile_id
  );

create view public.public_ranking_tracks with (security_barrier = true) as
select p.public_profile_id, p.nickname::text as nickname, rt.id as ranking_track_id,
       rt.strategy_version_id, rt.universe_version_id, rt.sell_rule_version_id,
       rt.status, rt.initial_capital, rt.cumulative_return, rt.max_drawdown,
       rt.trade_count, rt.started_at, rt.ended_at, rt.last_nav_at,
       rt.fill_model_version_id, rt.cost_model_version_id
from public.profiles p
join public.ranking_tracks rt on rt.user_id = p.user_id
where p.is_public and p.deleted_at is null and rt.is_public
  and not exists (
    select 1 from public.community_blocks b
    where b.user_id = auth.uid() and b.blocked_public_profile_id = p.public_profile_id
  );

create view public.public_ranked_executions with (security_barrier = true) as
select p.public_profile_id, rt.id as ranking_track_id, e.position_id,
       i.symbol, i.name_ko, e.side, e.executed_at, e.unit_price,
       e.quantity, e.fee, e.tax, e.event_type
from public.profiles p
join public.ranking_tracks rt on rt.user_id = p.user_id
join public.position_executions e on e.portfolio_id = rt.portfolio_id and e.user_id = p.user_id
join public.positions pos on pos.id = e.position_id
join public.instruments i on i.id = e.instrument_id
where p.is_public and p.deleted_at is null and rt.is_public
  and e.portfolio_kind = 'RANKED_PAPER'
  and not exists (
    select 1 from public.community_blocks b
    where b.user_id = auth.uid() and b.blocked_public_profile_id = p.public_profile_id
  )
  and (pos.status in ('CLOSED', 'ARCHIVED') or e.executed_at <= public.previous_completed_session_close());

create view public.public_portfolio_nav_curve with (security_barrier = true) as
select p.public_profile_id, rt.id as ranking_track_id, n.valuation_at, n.nav,
       n.realized_pnl, n.unrealized_pnl, n.fees, n.taxes, n.data_version
from public.profiles p
join public.ranking_tracks rt on rt.user_id = p.user_id
join public.portfolio_nav_snapshots n on n.ranking_track_id = rt.id
where p.is_public and p.deleted_at is null and rt.is_public
  and not exists (
    select 1 from public.community_blocks b
    where b.user_id = auth.uid() and b.blocked_public_profile_id = p.public_profile_id
  )
  and (rt.status <> 'ACTIVE' or n.valuation_at <= public.previous_completed_session_close());

create view public.public_combination_rankings with (security_barrier = true) as
select id, period, as_of, formula_version, dataset_version, engine_version,
       cost_model_version_id, fill_model_version_id, rows
from public.ranking_snapshots
where kind = 'COMBINATION' and is_published;

create view public.public_indicator_tiers with (security_barrier = true) as
select its.indicator_definition_id, i.code as indicator_code, i.name_ko,
       its.universe_version_id, its.period, its.tier, its.ablation_score,
       its.stability_score, its.deduplicated_frequency_score, its.total_score,
       its.sample_count, its.formula_version, its.dataset_version, its.as_of
from public.indicator_tier_snapshots its
join public.indicator_definitions i on i.id = its.indicator_definition_id
where its.is_published;

create view public.current_user_entitlements with (security_barrier = true) as
with current_identity as (
  select auth.uid() as user_id
), free_plan as (
  select pe.feature_id, pe.enabled, pe.limit_value
  from public.plan_entitlements pe
  join public.plans p on p.id = pe.plan_id
  where p.code = 'free' and p.is_active
), active_override as (
  select distinct on (ue.feature_id) ue.feature_id, ue.enabled, ue.limit_value, ue.source
  from public.user_entitlements ue, current_identity ci
  where ue.user_id = ci.user_id and ue.starts_at <= now()
    and (ue.expires_at is null or ue.expires_at > now())
  order by ue.feature_id, ue.starts_at desc
)
select ci.user_id, f.code as feature_code, f.name_ko,
       coalesce(ao.enabled, fp.enabled, false) as enabled,
       coalesce(ao.limit_value, fp.limit_value) as limit_value,
       coalesce(ao.source::text, 'PLAN') as source
from current_identity ci
cross join public.features f
left join free_plan fp on fp.feature_id = f.id
left join active_override ao on ao.feature_id = f.id
where ci.user_id is not null;

-- RLS: owner base tables, public data only through safe views.
do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'profiles','strategies','strategy_versions','strategy_rules','watchlist_items',
    'portfolios','sell_rule_sets','sell_rule_versions','signals','positions',
    'position_sell_rule_bindings','position_signals','signal_rule_matches','paper_orders',
    'paper_order_events','position_executions','portfolio_cash_ledger','position_adjustments',
    'portfolio_nav_snapshots','alert_settings','device_tokens','push_outbox','backtest_runs',
    'backtest_metrics','ranking_tracks','ranking_track_events','user_entitlements',
    'usage_counters','community_reports','community_blocks','audit_logs'
  ] loop
    execute format('alter table public.%I enable row level security', table_name);
    execute format('create policy owner_select on public.%I for select to authenticated using (user_id = auth.uid())', table_name);
  end loop;
end $$;

do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'profiles','strategies','strategy_versions','strategy_rules','watchlist_items',
    'sell_rule_sets','sell_rule_versions','alert_settings','device_tokens'
  ] loop
    execute format('create policy owner_insert on public.%I for insert to authenticated with check (user_id = auth.uid())', table_name);
    execute format('create policy owner_update on public.%I for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())', table_name);
  end loop;
end $$;

create policy owner_delete on public.watchlist_items for delete to authenticated using (user_id = auth.uid());
create policy owner_delete on public.device_tokens for delete to authenticated using (user_id = auth.uid());
create policy owner_insert on public.community_reports for insert to authenticated with check (user_id = auth.uid());
create policy owner_insert on public.community_blocks for insert to authenticated with check (user_id = auth.uid());
create policy owner_delete on public.community_blocks for delete to authenticated using (user_id = auth.uid());
create policy owner_delete on public.strategies for delete to authenticated using (user_id = auth.uid());
create policy owner_delete on public.strategy_versions for delete to authenticated using (user_id = auth.uid());
create policy owner_delete on public.strategy_rules for delete to authenticated using (user_id = auth.uid());
create policy owner_delete on public.sell_rule_sets for delete to authenticated using (user_id = auth.uid());
create policy owner_delete on public.sell_rule_versions for delete to authenticated using (user_id = auth.uid());

do $$
declare
  table_name text;
begin
  foreach table_name in array array['universe_definitions','universe_versions','universe_memberships'] loop
    execute format('alter table public.%I enable row level security', table_name);
    execute format('create policy visible_or_owner_select on public.%I for select to authenticated using (user_id is null or user_id = auth.uid())', table_name);
    execute format('create policy owner_insert on public.%I for insert to authenticated with check (user_id = auth.uid())', table_name);
    execute format('create policy owner_update on public.%I for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())', table_name);
    execute format('create policy owner_delete on public.%I for delete to authenticated using (user_id = auth.uid())', table_name);
  end loop;
end $$;

do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'instruments','market_sessions','candles','indicator_definitions',
    'paper_fill_model_versions','cost_model_versions','corporate_actions',
    'plans','features','plan_entitlements','feature_flags'
  ] loop
    execute format('alter table public.%I enable row level security', table_name);
    execute format('create policy authenticated_read on public.%I for select to authenticated using (true)', table_name);
  end loop;
end $$;

alter table public.ranking_snapshots enable row level security;
alter table public.indicator_tier_snapshots enable row level security;

revoke all on all tables in schema public from anon;
grant usage on schema public to anon, authenticated;
grant select on all tables in schema public to authenticated;
grant insert, update on public.profiles, public.strategies, public.strategy_versions,
  public.strategy_rules, public.watchlist_items, public.universe_definitions,
  public.universe_versions, public.universe_memberships, public.sell_rule_sets,
  public.sell_rule_versions, public.alert_settings, public.device_tokens to authenticated;
grant delete on public.strategies, public.strategy_versions, public.strategy_rules,
  public.watchlist_items, public.universe_definitions, public.universe_versions,
  public.universe_memberships, public.sell_rule_sets, public.sell_rule_versions,
  public.device_tokens to authenticated;
grant insert on public.community_reports to authenticated;
grant insert, delete on public.community_blocks to authenticated;

grant select on public.public_profiles, public.public_strategy_versions,
  public.public_strategy_rules, public.public_ranking_tracks,
  public.public_ranked_executions, public.public_portfolio_nav_curve,
  public.public_combination_rankings, public.public_indicator_tiers to anon, authenticated;
grant select on public.current_user_entitlements to authenticated;
grant execute on function public.previous_completed_session_close() to anon, authenticated;

revoke all on function public.handle_new_auth_user() from public;
revoke all on function public.validate_cash_ledger() from public;
revoke all on function public.apply_cash_ledger_cache() from public;
revoke all on function public.finalize_paper_order_fill() from public;
revoke all on function public.close_position_dependents() from public;
revoke all on function public.purge_user_account(uuid) from public;
revoke all on function public.apply_paper_fill(uuid, numeric, timestamptz, text) from public;
grant execute on function public.purge_user_account(uuid) to service_role;
grant execute on function public.apply_paper_fill(uuid, numeric, timestamptz, text) to service_role;

commit;
