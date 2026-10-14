import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'DELETE';
export const OPTIONS = () => preflight(M);

export async function DELETE(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);

  // PostgREST requires a filter on delete; this matches every row
  const { error } = await db().from('notifications').delete().not('id', 'is', null);
  if (error) return json({ error: 'Failed to clear history' }, 502, M);
  return json({ ok: true }, 200, M);
}
