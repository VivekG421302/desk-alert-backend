-- Run after schema.sql. Idempotent.
-- 1) Status tracking on notifications
alter table public.notifications add column if not exists status  text not null default 'new';
alter table public.notifications add column if not exists read_at timestamptz;
alter table public.notifications drop constraint if exists notifications_status_check;
alter table public.notifications add constraint notifications_status_check check (status in ('new', 'read'));
create index if not exists notifications_status_idx on public.notifications (status);

-- 2) Event log written by a trigger (insert / status change / delete)
create table if not exists public.notification_events (
  id              bigint generated always as identity primary key,
  created_at      timestamptz not null default timezone('utc', now()),
  notification_id uuid,
  event           text not null,          -- 'created' | 'status_changed' | 'deleted'
  detail          text
);
create index if not exists notification_events_created_idx on public.notification_events (created_at desc);

create or replace function public.log_notification_event() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    insert into notification_events(notification_id, event, detail) values (new.id, 'created', new.type || ' from ' || new.app_name);
  elsif tg_op = 'UPDATE' and old.status is distinct from new.status then
    insert into notification_events(notification_id, event, detail) values (new.id, 'status_changed', old.status || ' -> ' || new.status);
  elsif tg_op = 'DELETE' then
    insert into notification_events(notification_id, event, detail) values (old.id, 'deleted', null);
  end if;
  return null;
end $$;

drop trigger if exists notifications_log on public.notifications;
create trigger notifications_log after insert or update or delete on public.notifications
  for each row execute function public.log_notification_event();

-- RLS: log table is backend-only (no policies = no anon access)
alter table public.notification_events enable row level security;
