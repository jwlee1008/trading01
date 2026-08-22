create table if not exists public.worker_task_runs (
  id uuid primary key default gen_random_uuid(),
  task_name text not null,
  run_key text not null,
  status text not null check (status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  last_error text,
  result jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (task_name, run_key),
  check ((status = 'RUNNING' and finished_at is null) or (status <> 'RUNNING' and finished_at is not null))
);

create index if not exists worker_task_runs_recent_idx
  on public.worker_task_runs(task_name, started_at desc);

alter table public.worker_task_runs enable row level security;
revoke all on public.worker_task_runs from anon, authenticated;

create trigger worker_task_runs_set_updated_at
before update on public.worker_task_runs
for each row execute function public.set_updated_at();
