import crypto from 'node:crypto';
import { createClient } from '@supabase/supabase-js';

export const dynamic = 'force-dynamic';

export function corsHeaders(methods) {
  return {
    'Access-Control-Allow-Origin': process.env.ALLOWED_ORIGIN || '*',
    'Access-Control-Allow-Methods': `${methods}, OPTIONS`,
    'Access-Control-Allow-Headers': 'Content-Type, x-app-password',
    'Access-Control-Max-Age': '86400',
    Vary: 'Origin',
  };
}

export function json(data, status, methods) {
  return Response.json(data, {
    status,
    headers: { ...corsHeaders(methods), 'Cache-Control': 'no-store' },
  });
}

export function preflight(methods) {
  return new Response(null, { status: 204, headers: corsHeaders(methods) });
}

// Constant-time comparison (hash both sides so lengths always match)
export function passwordOk(candidate) {
  const expected = process.env.APP_PASSWORD;
  if (!expected || typeof candidate !== 'string' || !candidate) return false;
  const a = crypto.createHash('sha256').update(candidate).digest();
  const b = crypto.createHash('sha256').update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

let client;
export function db() {
  if (!client) {
    const { SUPABASE_URL, SUPABASE_KEY } = process.env;
    if (!SUPABASE_URL || !SUPABASE_KEY) throw new Error('Supabase env vars missing');
    client = createClient(SUPABASE_URL, SUPABASE_KEY, { auth: { persistSession: false } });
  }
  return client;
}

export function misconfigured() {
  return !process.env.APP_PASSWORD || !process.env.SUPABASE_URL || !process.env.SUPABASE_KEY;
}
