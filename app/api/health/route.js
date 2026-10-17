import { json, preflight, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'GET';
export const OPTIONS = () => preflight(M);

// Open check, reveals nothing sensitive: confirms env vars, Supabase connectivity and whether the reply feature's database update was run.
export async function GET() {
  if (misconfigured()) return json({ ok: false, error: 'Missing env vars' }, 500, M);
  const base = await db().from('notifications').select('id', { head: true, count: 'exact' }).limit(1);
  if (base.error) return json({ ok: false, error: 'Supabase unreachable or table missing' }, 502, M);

  const cols = await db().from('notifications').select('dedupe_key, reply_key, can_reply').limit(1);
  const rep = await db().from('replies').select('id').limit(1);
  const schema = { notifications_columns: !cols.error, replies_table: !rep.error };
  schema.replies_ready = schema.notifications_columns && schema.replies_table;
  return json({ ok: true, schema }, 200, M);
}
