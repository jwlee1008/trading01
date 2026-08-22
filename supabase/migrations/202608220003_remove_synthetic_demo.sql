begin;

do $$
begin
  if exists (
    with synthetic as (
      select id from public.instruments
      where provider_refs->>'synthetic' = 'true' or symbol like 'DEMO%'
    )
    select 1 from public.signals where instrument_id in (select id from synthetic)
    union all select 1 from public.positions where instrument_id in (select id from synthetic)
    union all select 1 from public.paper_orders where instrument_id in (select id from synthetic)
    union all select 1 from public.position_executions where instrument_id in (select id from synthetic)
    union all select 1 from public.watchlist_items where instrument_id in (select id from synthetic)
    union all select 1 from public.alert_settings where instrument_id in (select id from synthetic)
  ) then
    raise exception 'Synthetic demo instruments are referenced by user or trading data; clean those references explicitly first';
  end if;

  if exists (
    with demo_versions as (
      select uv.id from public.universe_versions uv
      join public.universe_definitions ud on ud.id=uv.universe_definition_id
      where ud.kind='DEMO_TOP_50'::public.universe_kind
    )
    select 1 from public.strategy_versions where universe_version_id in (select id from demo_versions)
    union all select 1 from public.positions where universe_version_id in (select id from demo_versions)
    union all select 1 from public.backtest_runs where universe_version_id in (select id from demo_versions)
    union all select 1 from public.ranking_tracks where universe_version_id in (select id from demo_versions)
  ) then
    raise exception 'Demo universe is referenced by strategy or trading data; clean those references explicitly first';
  end if;
end $$;

delete from public.indicator_tier_snapshots
where universe_version_id in (
  select uv.id from public.universe_versions uv
  join public.universe_definitions ud on ud.id=uv.universe_definition_id
  where ud.kind='DEMO_TOP_50'::public.universe_kind
);

alter table public.universe_memberships disable trigger universe_memberships_guard;
delete from public.universe_memberships
where universe_version_id in (
  select uv.id from public.universe_versions uv
  join public.universe_definitions ud on ud.id=uv.universe_definition_id
  where ud.kind='DEMO_TOP_50'::public.universe_kind
);
alter table public.universe_memberships enable trigger universe_memberships_guard;

alter table public.universe_versions disable trigger universe_versions_guard;
delete from public.universe_versions
where universe_definition_id in (
  select id from public.universe_definitions where kind='DEMO_TOP_50'::public.universe_kind
);
alter table public.universe_versions enable trigger universe_versions_guard;

delete from public.universe_definitions where kind='DEMO_TOP_50'::public.universe_kind;

create temporary table synthetic_instruments on commit drop as
select id from public.instruments
where provider_refs->>'synthetic' = 'true' or symbol like 'DEMO%';

delete from public.candles where provider='synthetic-demo' or instrument_id in (select id from synthetic_instruments);
delete from public.market_cap_snapshots where instrument_id in (select id from synthetic_instruments);
delete from public.corporate_actions where instrument_id in (select id from synthetic_instruments);
delete from public.instruments where id in (select id from synthetic_instruments);

commit;
