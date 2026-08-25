-- Paper trading and automated ranking tracks were retired. User rankings now
-- derive from self-reported MANUAL_LIVE executions in the API query.

drop view if exists public.public_portfolio_nav_curve;
drop view if exists public.public_ranked_executions;
drop view if exists public.public_ranking_tracks;

drop trigger if exists position_executions_finalize_order on public.position_executions;
drop trigger if exists paper_orders_validate on public.paper_orders;
drop trigger if exists paper_orders_immutable_terms on public.paper_orders;
drop trigger if exists paper_orders_set_updated_at on public.paper_orders;
drop trigger if exists paper_order_events_append_only on public.paper_order_events;
drop trigger if exists cash_ledger_validate on public.portfolio_cash_ledger;
drop trigger if exists cash_ledger_apply_cache on public.portfolio_cash_ledger;
drop trigger if exists cash_ledger_append_only on public.portfolio_cash_ledger;
drop trigger if exists ranking_tracks_validate on public.ranking_tracks;
drop trigger if exists ranking_tracks_set_updated_at on public.ranking_tracks;
drop trigger if exists ranking_track_events_append_only on public.ranking_track_events;
drop trigger if exists nav_snapshots_validate on public.portfolio_nav_snapshots;

delete from public.portfolio_nav_snapshots;
delete from public.ranking_track_events;
delete from public.ranking_tracks;
delete from public.portfolio_cash_ledger;
delete from public.paper_order_events;
delete from public.position_executions where portfolio_kind in ('SANDBOX_PAPER','RANKED_PAPER');
delete from public.paper_orders;
delete from public.positions where portfolio_kind in ('SANDBOX_PAPER','RANKED_PAPER');
delete from public.portfolios where kind in ('SANDBOX_PAPER','RANKED_PAPER');

alter table public.position_executions drop constraint if exists position_executions_check;
drop index if exists public.position_executions_one_fill_per_order_uidx;
alter table public.position_executions drop column if exists paper_order_id;
alter table public.position_executions
  add constraint position_executions_manual_only_check check (portfolio_kind='MANUAL_LIVE');
alter table public.positions
  add constraint positions_manual_only_check check (portfolio_kind='MANUAL_LIVE');
alter table public.portfolios
  add constraint portfolios_manual_only_check check (kind='MANUAL_LIVE');

create or replace function public.validate_position_execution()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
  p_user uuid;
  p_kind public.portfolio_kind;
  position_row public.positions%rowtype;
  target_row public.position_executions%rowtype;
begin
  select user_id,kind into p_user,p_kind from public.portfolios where id=new.portfolio_id;
  if not found or p_user is distinct from new.user_id or p_kind is distinct from new.portfolio_kind
     or p_kind <> 'MANUAL_LIVE' then
    raise exception 'execution portfolio owner/kind mismatch' using errcode='23514';
  end if;
  select * into position_row from public.positions p
   where p.id=new.position_id and p.portfolio_id=new.portfolio_id
     and p.user_id=new.user_id and p.instrument_id=new.instrument_id
     and p.portfolio_kind='MANUAL_LIVE' for update;
  if not found then
    raise exception 'execution position mismatch' using errcode='23514';
  end if;
  if new.event_type='EXECUTION' and new.side='BUY'
     and position_row.status not in ('OPEN','PARTIALLY_CLOSED') then
    raise exception 'buy execution position status mismatch' using errcode='23514';
  end if;
  if new.event_type='EXECUTION' and new.side='SELL'
     and (position_row.status not in ('OPEN','EXIT_PENDING','PARTIALLY_CLOSED')
          or new.quantity>position_row.quantity) then
    raise exception 'sell execution position status/quantity mismatch' using errcode='23514';
  end if;
  if new.reverses_execution_id is not null then
    select * into target_row from public.position_executions where id=new.reverses_execution_id;
    if not found or target_row.portfolio_id<>new.portfolio_id
       or target_row.position_id<>new.position_id
       or target_row.instrument_id<>new.instrument_id or target_row.side<>new.side then
      raise exception 'execution correction target mismatch' using errcode='23514';
    end if;
  end if;
  return new;
end;
$$;

drop index if exists public.positions_ranked_one_open_instrument_uidx;
drop table if exists public.portfolio_nav_snapshots;
drop table if exists public.ranking_track_events;
drop table if exists public.ranking_tracks;
drop table if exists public.portfolio_cash_ledger;
drop table if exists public.paper_order_events;
drop table if exists public.paper_orders;

drop function if exists public.validate_nav_snapshot();
drop function if exists public.validate_ranking_track();
drop function if exists public.apply_paper_fill(uuid,numeric,timestamptz,text);
drop function if exists public.finalize_paper_order_fill();
drop function if exists public.validate_paper_order();
drop function if exists public.guard_paper_order_update();
drop function if exists public.validate_cash_ledger();
drop function if exists public.apply_cash_ledger_cache();

delete from public.worker_task_requests
  where task_name in ('ranking-nav','ranked-buy','paper-fill');
delete from public.worker_task_runs
  where task_name in ('ranking-nav','ranked-buy','paper-fill');
alter table public.worker_task_runs drop constraint if exists worker_task_runs_task_name_check;
alter table public.worker_task_runs add constraint worker_task_runs_task_name_check
  check (task_name in ('market-data','signal','ranking-snapshot','sell-signal','notification'));
alter table public.worker_task_requests drop constraint if exists worker_task_requests_task_name_check;
alter table public.worker_task_requests add constraint worker_task_requests_task_name_check
  check (task_name in ('market-data','signal','ranking-snapshot','sell-signal','notification'));
