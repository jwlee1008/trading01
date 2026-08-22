create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  sandbox_portfolio_id uuid;
begin
  insert into public.profiles(user_id, nickname)
  values (new.id, '사용자-' || replace(left(new.id::text, 18), '-', ''))
  on conflict (user_id) do nothing;

  insert into public.portfolios(user_id, kind, name, initial_cash, cash_balance)
  values (new.id, 'MANUAL_LIVE', '실제 수동 보유', 0, 0)
  on conflict (user_id, kind, name) do nothing;

  insert into public.portfolios(user_id, kind, name, initial_cash, cash_balance)
  values (new.id, 'SANDBOX_PAPER', '연습 페이퍼', 10000000, 0)
  on conflict (user_id, kind, name) do nothing;

  select id into sandbox_portfolio_id
    from public.portfolios
   where user_id = new.id
     and kind = 'SANDBOX_PAPER'
     and name = '연습 페이퍼';

  if not exists (
    select 1 from public.portfolio_cash_ledger where portfolio_id = sandbox_portfolio_id
  ) then
    insert into public.portfolio_cash_ledger
      (user_id, portfolio_id, portfolio_kind, sequence_no, event_type, amount,
       resulting_balance, idempotency_key, occurred_at)
    values
      (new.id, sandbox_portfolio_id, 'SANDBOX_PAPER', 1, 'INITIAL_CAPITAL', 10000000,
       10000000, 'bootstrap-initial-capital-v1', now());
  end if;

  if not exists (
    select 1
      from public.alert_settings
     where user_id = new.id
       and strategy_id is null
       and instrument_id is null
  ) then
    insert into public.alert_settings(user_id)
    values (new.id);
  end if;

  return new;
end;
$$;

insert into public.portfolios(user_id, kind, name, initial_cash, cash_balance)
select users.id, bootstrap.kind, bootstrap.name, bootstrap.initial_cash, bootstrap.cash_balance
  from auth.users as users
 cross join (
   values
     ('MANUAL_LIVE'::public.portfolio_kind, '실제 수동 보유'::text, 0::numeric, 0::numeric),
     ('SANDBOX_PAPER'::public.portfolio_kind, '연습 페이퍼'::text, 10000000::numeric, 0::numeric)
 ) as bootstrap(kind, name, initial_cash, cash_balance)
on conflict (user_id, kind, name) do nothing;

insert into public.portfolio_cash_ledger
  (user_id, portfolio_id, portfolio_kind, sequence_no, event_type, amount,
   resulting_balance, idempotency_key, occurred_at)
select portfolios.user_id, portfolios.id, portfolios.kind, 1, 'INITIAL_CAPITAL',
       portfolios.initial_cash, portfolios.initial_cash, 'bootstrap-initial-capital-v1',
       portfolios.created_at
  from public.portfolios as portfolios
 where portfolios.kind = 'SANDBOX_PAPER'
   and portfolios.initial_cash > 0
   and not exists (
     select 1
       from public.portfolio_cash_ledger as ledger
      where ledger.portfolio_id = portfolios.id
   );

insert into public.alert_settings(user_id)
select users.id
  from auth.users as users
 where not exists (
   select 1
     from public.alert_settings as settings
    where settings.user_id = users.id
      and settings.strategy_id is null
      and settings.instrument_id is null
 );
