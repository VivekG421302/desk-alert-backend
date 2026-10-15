import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'DELETE';
export const OPTIONS = () => preflight(M);

// Delete one notification: DELETE /api/notification?id=<uuid>
export async function DELETE(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);

  const id = new URL(req.url).searchParams.get('id') || '';
  if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: 'Valid id required' }, 400, M);

  const { error } = await db().from('notifications').delete().eq('id', id);
  if (error) return json({ error: 'Failed to delete' }, 502, M);
  return json({ ok: true }, 200, M);
}
