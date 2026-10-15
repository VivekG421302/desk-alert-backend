import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'GET, POST';
export const OPTIONS = () => preflight(M);

// Dashboard queues a reply
export async function POST(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);

  let body; try { body = await req.json(); } catch { return json({ error: 'Invalid JSON' }, 400, M); }
  const text = typeof body?.text === 'string' ? body.text.trim().slice(0, 1000) : '';
  if (!text || typeof body?.notificationId !== 'string') return json({ error: 'notificationId and text required' }, 400, M);

  const { data: n } = await db().from('notifications').select('id, reply_key, can_reply').eq('id', body.notificationId).maybeSingle();
  if (!n || !n.can_reply || !n.reply_key) return json({ error: 'This message cannot be replied to' }, 400, M);

  const { data, error } = await db().from('replies')
    .insert({ notification_id: n.id, reply_key: n.reply_key, text }).select('id').single();
  if (error) return json({ error: 'Failed to queue reply' }, 502, M);
  return json({ ok: true, id: data.id }, 201, M);
}

// Dashboard checks a reply's status
export async function GET(req) {
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);
  const id = new URL(req.url).searchParams.get('id');
  if (!id) return json({ error: 'id required' }, 400, M);
  const { data } = await db().from('replies').select('status, error').eq('id', id).maybeSingle();
  return data ? json(data, 200, M) : json({ error: 'Not found' }, 404, M);
}
