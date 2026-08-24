create unique index if not exists worker_task_requests_one_active_task_uidx
  on public.worker_task_requests(task_name)
  where status in ('PENDING','RUNNING');
