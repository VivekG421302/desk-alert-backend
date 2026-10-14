import { json, preflight, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'GET';
export const OPTIONS = () => preflight(M);

// Open check, reveals nothing sensitive: confirms env vars and Supabase connectivity.
export async function GET() {
  if (misconfigured()) return json({ ok: false, error: 'Missing env vars' }, 500, M);
  const { error } = await db().from('notifications').select('id', { head: true, count: 'exact' }).limit(1);
  if (error) return json({ ok: false, error: 'Supabase unreachable or table missing' }, 502, M);
  return json({ ok: true }, 200, M);
}
