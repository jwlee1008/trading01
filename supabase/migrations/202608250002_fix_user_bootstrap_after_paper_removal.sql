-- Keep auth signup bootstrap aligned with the manual-only portfolio model.
-- The previous function still created SANDBOX_PAPER data and wrote to the
-- retired portfolio_cash_ledger table, causing auth.users inserts to fail.

create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  requested_nickname text;
begin
  requested_nickname := nullif(btrim(new.raw_user_meta_data ->> 'nickname'), '');

  insert into public.profiles(user_id, nickname)
  values (
    new.id,
    coalesce(requested_nickname, '사용자-' || replace(left(new.id::text, 18), '-', ''))
  )
  on conflict (user_id) do nothing;

  insert into public.portfolios(user_id, kind, name, initial_cash, cash_balance)
  values (new.id, 'MANUAL_LIVE', '실제 수동 보유', 0, 0)
  on conflict (user_id, kind, name) do nothing;

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

revoke all on function public.handle_new_auth_user() from public;
