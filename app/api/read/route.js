import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'POST';
export const OPTIONS = () => preflight(M);

// Marks every 'new' notification as read
export async function POST(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);
  const { error } = await db().from('notifications')
    .update({ status: 'read', read_at: new Date().toISOString() }).eq('status', 'new');
  if (error) return json({ error: 'Failed to update' }, 502, M);
  return json({ ok: true }, 200, M);
}
