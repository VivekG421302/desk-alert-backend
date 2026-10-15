import crypto from 'node:crypto';
import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'POST';
const LIMITS = { appName: 100, title: 300, message: 4000, replyKey: 100, clientId: 200 };
const MAX_AGE_MS = 7 * 24 * 3600 * 1000;
const TYPES = new Set(['CALL', 'MESSAGE']);
const DEDUPE_WINDOW_MS = 20_000;

// MacroDroid often sends calls as plain notifications: detect them by text
const looksLikeCall = (s) => /incoming.*call|is calling|calling\.\.\./i.test(s) && !/missed|ongoing|ended|declined/i.test(s);

export const OPTIONS = () => preflight(M);

export async function POST(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);

  let body;
  try { body = await req.json(); } catch { return json({ error: 'Invalid JSON' }, 400, M); }
  if (!body || typeof body !== 'object') return json({ error: 'Invalid body' }, 400, M);
  if (!passwordOk(body.secret)) return json({ error: 'Unauthorized' }, 401, M);

  const clean = (v, max, fallback) =>
    (typeof v === 'string' && v.trim() ? v.trim() : fallback).slice(0, max);

  const row = {
    app_name: clean(body.appName, LIMITS.appName, 'Unknown'),
    title: clean(body.title, LIMITS.title, 'Unknown'),
    message: clean(body.message, LIMITS.message, '(no content)'),
    type: TYPES.has(String(body.type || '').toUpperCase()) ? String(body.type).toUpperCase() : 'MESSAGE',
  };
  if (row.type !== 'CALL' && looksLikeCall(`${row.title} ${row.message}`)) row.type = 'CALL';

  // Original time of the notification on the phone (kept when it had to be re-sent after being offline)
  const postedAt = Number(body.postedAt);
  const now = Date.now();
  if (Number.isFinite(postedAt) && postedAt > now - MAX_AGE_MS && postedAt < now + 5 * 60 * 1000) {
    row.created_at = new Date(postedAt).toISOString();
  }

  const replyKey = clean(body.replyKey, LIMITS.replyKey, '');
  // Exact idempotency when the phone app sends a clientId; time-window fallback for other senders (e.g. MacroDroid)
  const clientId = clean(body.clientId, LIMITS.clientId, '');
  const bucket = Math.floor(now / DEDUPE_WINDOW_MS);
  const dedupe_key = crypto.createHash('sha256')
    .update(clientId ? 'c|' + clientId : [row.app_name, row.title, row.message, row.type, bucket].join('|')).digest('hex');

  const extra = { dedupe_key, ...(replyKey && row.type !== 'CALL' ? { reply_key: replyKey, can_reply: true } : {}) };
  let { data, error } = await db().from('notifications').insert({ ...row, ...extra }).select('id').single();

  if (error?.code === '23505') {                       // same alert already stored in this window
    if (extra.reply_key) await db().from('notifications').update({ reply_key: replyKey, can_reply: true }).eq('dedupe_key', dedupe_key);
    const { data: existing } = await db().from('notifications').select('id').eq('dedupe_key', dedupe_key).maybeSingle();
    return json({ ok: true, duplicate: true, id: existing?.id }, 200, M);
  }
  if (error && (error.code === '42703' || error.code === 'PGRST204')) {   // migration 003 not run yet
    ({ data, error } = await db().from('notifications').insert(row).select('id').single());
  }
  if (error) {
    console.error('Supabase insert failed:', error.message);
    return json({ error: 'Failed to store notification' }, 502, M);
  }
  return json({ ok: true, id: data.id }, 201, M);
}
