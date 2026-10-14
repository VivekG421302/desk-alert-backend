-- Run after creating the table from the spec.
-- RLS on: the service-role key (backend) bypasses it; the browser (anon key)
-- may only SELECT, which Supabase Realtime needs for postgres_changes.
alter table notifications enable row level security;

create policy "anon can read notifications"
  on notifications for select to anon using (true);

-- Realtime DELETE events need the full row identity
alter table notifications replica identity full;
