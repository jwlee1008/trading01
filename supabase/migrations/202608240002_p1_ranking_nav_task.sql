alter table public.worker_task_requests
  drop constraint if exists worker_task_requests_task_name_check;

alter table public.worker_task_requests
  add constraint worker_task_requests_task_name_check
  check (task_name in ('market-data','signal','ranking-snapshot','ranking-nav','ranked-buy','sell-signal','paper-fill','notification'));
