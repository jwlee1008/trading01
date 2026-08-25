-- Strategy deletion may detach immutable manual executions from the deleted
-- strategy/signal, while preserving every financial execution field.
create or replace function public.guard_position_execution_append_only()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if tg_op = 'DELETE'
     and coalesce(current_setting('app.account_purge', true), 'off') = 'on' then
    return old;
  end if;

  if tg_op = 'UPDATE'
     and coalesce(current_setting('app.strategy_purge', true), 'off') = 'on'
     and new.source_signal_id is null
     and (new.strategy_version_id is null or new.strategy_version_id is not distinct from old.strategy_version_id)
     and (to_jsonb(new) - 'source_signal_id' - 'strategy_version_id')
         = (to_jsonb(old) - 'source_signal_id' - 'strategy_version_id') then
    return new;
  end if;

  raise exception '% is append-only; add reversal/correction event', tg_table_name
    using errcode = '55000';
end;
$$;

drop trigger if exists position_executions_append_only on public.position_executions;
create trigger position_executions_append_only
before update or delete on public.position_executions
for each row execute function public.guard_position_execution_append_only();
