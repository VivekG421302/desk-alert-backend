import { json, preflight, passwordOk } from '../../../lib/http';

export const dynamic = 'force-dynamic';
const M = 'POST';
export const OPTIONS = () => preflight(M);

export async function POST(req) {
  const ok = passwordOk(req.headers.get('x-app-password'));
  return ok ? json({ ok: true }, 200, M) : json({ error: 'Unauthorized' }, 401, M);
}
