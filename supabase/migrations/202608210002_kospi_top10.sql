begin;

alter type public.universe_kind add value if not exists 'KOSPI_TOP_10';

delete from public.market_cap_snapshots where rank > 10;
alter table public.market_cap_snapshots drop constraint if exists market_cap_snapshots_rank_check;
alter table public.market_cap_snapshots
  add constraint market_cap_snapshots_rank_check check (rank between 1 and 10);

commit;
