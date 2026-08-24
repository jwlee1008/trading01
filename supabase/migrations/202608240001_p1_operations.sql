create table if not exists public.worker_task_requests (
  id uuid primary key default gen_random_uuid(),
  task_name text not null check (task_name in ('market-data','signal','ranking-snapshot','ranking-nav','ranked-buy','sell-signal','paper-fill','notification')),
  status text not null default 'PENDING' check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  requested_by uuid references auth.users(id) on delete set null,
  source_run_id uuid references public.worker_task_runs(id) on delete set null,
  run_key text not null unique,
  requested_at timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz,
  last_error text,
  check (
    (status = 'PENDING' and started_at is null and finished_at is null)
    or (status = 'RUNNING' and started_at is not null and finished_at is null)
    or (status in ('SUCCEEDED','FAILED','CANCELLED') and finished_at is not null)
  )
);

create index if not exists worker_task_requests_pending_idx
  on public.worker_task_requests(status, requested_at) where status = 'PENDING';

alter table public.worker_task_requests enable row level security;
revoke all on public.worker_task_requests from anon, authenticated;

alter table public.profiles
  add column if not exists selected_universe_version_id uuid references public.universe_versions(id) on delete set null;
alter table public.profiles
  add column if not exists disclose_open_positions boolean not null default false;

comment on table public.worker_task_requests is 'API에서 요청하고 Worker가 가져가는 운영 작업 큐';
comment on column public.profiles.selected_universe_version_id is '사용자가 마지막으로 선택한 확정 유니버스 버전';
