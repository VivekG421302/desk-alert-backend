-- Desk Alert Bridge — full schema. Safe to re-run (idempotent).
-- Supabase Dashboard -> SQL Editor -> paste -> Run.

create extension if not exists pgcrypto;

create table if not exists public.notifications (
  id         uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default timezone('utc', now()),
  app_name   text not null,
  title      text not null,
  message    text not null,
  type       text not null default 'MESSAGE'
);

-- Enforce allowed types
alter table public.notifications drop constraint if exists notifications_type_check;
alter table public.notifications
  add constraint notifications_type_check check (type in ('CALL', 'MESSAGE'));

-- Fast "latest 100" queries
create index if not exists notifications_created_at_idx
  on public.notifications (created_at desc);

-- Row Level Security:
--  * backend uses the secret/service-role key, which bypasses RLS
--  * browser uses the publishable/anon key: read-only (needed for Realtime)
alter table public.notifications enable row level security;

drop policy if exists "anon can read notifications" on public.notifications;
create policy "anon can read notifications"
  on public.notifications for select to anon using (true);

-- Realtime: include DELETE payloads and publish the table
alter table public.notifications replica identity full;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'notifications'
  ) then
    alter publication supabase_realtime add table public.notifications;
  end if;
end $$;

-- Optional: auto-delete alerts older than 30 days (enable pg_cron under Database -> Extensions first)
-- select cron.schedule('purge-old-notifications', '0 3 * * *',
--   $$delete from public.notifications where created_at < now() - interval '30 days'$$);
