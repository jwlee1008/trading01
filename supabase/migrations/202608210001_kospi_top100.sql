begin;

alter type public.universe_kind add value if not exists 'KOSPI_TOP_100';

create table public.market_cap_snapshots (
  session_date date not null,
  instrument_id uuid not null references public.instruments(id),
  market_cap numeric(30,0) not null check (market_cap > 0),
  rank integer not null check (rank between 1 and 100),
  source text not null,
  source_revision text not null,
  received_at timestamptz not null default now(),
  primary key (session_date, instrument_id),
  unique (session_date, rank)
);

create index market_cap_snapshots_instrument_date_idx
  on public.market_cap_snapshots(instrument_id, session_date desc);

alter table public.market_cap_snapshots enable row level security;
create policy authenticated_read on public.market_cap_snapshots
  for select to authenticated using (true);
grant select on public.market_cap_snapshots to authenticated;

commit;
