import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'GET';
export const OPTIONS = () => preflight(M);

export async function GET(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);

  const { data, error } = await db()
    .from('notifications').select('*').order('created_at', { ascending: false }).limit(100);
  if (error) return json({ error: 'Failed to load history' }, 502, M);
  return json({ items: data }, 200, M);
}
