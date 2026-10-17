import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'GET, POST';
export const OPTIONS = () => preflight(M);

// Phone app: fetch pending replies
export async function GET(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);
  // A reply the phone did not pick up within 45 s is stale: expire it so it can never be sent late by surprise
  const cutoff = new Date(Date.now() - 45_000).toISOString();
  await db().from('replies').update({ status: 'failed', error: 'Expired: phone did not respond in time' }).eq('status', 'pending').lt('created_at', cutoff);
  const { data, error } = await db().from('replies')
    .select('id, reply_key, text').eq('status', 'pending').order('created_at').limit(20);
  if (error) return json({ error: 'Failed to load replies' }, 502, M);
  return json({ items: data }, 200, M);
}

// Phone app: report result { id, ok, error? }
export async function POST(req) {
  if (!passwordOk(req.headers.get('x-app-password'))) return json({ error: 'Unauthorized' }, 401, M);
  let body; try { body = await req.json(); } catch { return json({ error: 'Invalid JSON' }, 400, M); }
  if (typeof body?.closeReplyKey === 'string' && body.closeReplyKey) {      // chat notification disappeared on the phone
    await db().from('notifications').update({ can_reply: false }).eq('reply_key', body.closeReplyKey.slice(0, 100));
    return json({ ok: true }, 200, M);
  }
  if (typeof body?.id !== 'string') return json({ error: 'id required' }, 400, M);
  const { error } = await db().from('replies')
    .update({ status: body.ok ? 'sent' : 'failed', error: body.ok ? null : String(body.error || 'Failed').slice(0, 200) })
    .eq('id', body.id);
  if (error) return json({ error: 'Failed to update' }, 502, M);
  return json({ ok: true }, 200, M);
}
