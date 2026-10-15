-- Run after 002. Idempotent.
-- Dedupe key + reply support on notifications
alter table public.notifications add column if not exists dedupe_key text;
alter table public.notifications add column if not exists reply_key  text;     -- random token made by the phone app (not the WhatsApp key)
alter table public.notifications add column if not exists can_reply  boolean not null default false;
create unique index if not exists notifications_dedupe_key_idx on public.notifications (dedupe_key);

-- Reply queue: dashboard writes 'pending', phone app picks up and marks sent/failed
create table if not exists public.replies (
  id              uuid primary key default gen_random_uuid(),
  created_at      timestamptz not null default timezone('utc', now()),
  notification_id uuid references public.notifications(id) on delete cascade,
  reply_key       text not null,
  text            text not null,
  status          text not null default 'pending' check (status in ('pending','sent','failed')),
  error           text
);
create index if not exists replies_status_idx on public.replies (status);
alter table public.replies enable row level security;   -- backend-only, no anon policy
