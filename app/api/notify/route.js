import { json, preflight, passwordOk, db, misconfigured } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'POST';
const LIMITS = { appName: 100, title: 300, message: 4000 };
const TYPES = new Set(['CALL', 'MESSAGE']);

export const OPTIONS = () => preflight(M);

export async function POST(req) {
  if (misconfigured()) return json({ error: 'Server not configured' }, 500, M);

  let body;
  try { body = await req.json(); } catch { return json({ error: 'Invalid JSON' }, 400, M); }
  if (!body || typeof body !== 'object') return json({ error: 'Invalid body' }, 400, M);

  if (!passwordOk(body.secret)) return json({ error: 'Unauthorized' }, 401, M);

  const clean = (v, max, fallback) =>
    (typeof v === 'string' && v.trim() ? v.trim() : fallback).slice(0, max);

  const type = String(body.type || 'MESSAGE').trim().toUpperCase();
  const row = {
    app_name: clean(body.appName, LIMITS.appName, 'Unknown'),
    title: clean(body.title, LIMITS.title, 'Unknown'),
    message: clean(body.message, LIMITS.message, '(no content)'),
    type: TYPES.has(type) ? type : 'MESSAGE',
  };

  const { data, error } = await db().from('notifications').insert(row).select().single();
  if (error) {
    console.error('Supabase insert failed:', error.message);
    return json({ error: 'Failed to store notification' }, 502, M);
  }
  return json({ ok: true, id: data.id }, 201, M);
}
