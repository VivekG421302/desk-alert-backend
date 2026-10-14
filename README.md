# Desk Alert Bridge — Backend (Next.js on Vercel)

Validates `APP_PASSWORD` and stores phone notifications in Supabase.

## Endpoints
| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/notify` | `secret` in JSON body | MacroDroid -> insert notification |
| POST | `/api/verify` | `x-app-password` header | Dashboard login check |
| GET | `/api/history` | `x-app-password` header | Last 100 notifications |
| GET | `/api/health` | none | Checks env vars + Supabase connection |
| DELETE | `/api/clear` | `x-app-password` header | Clear all history |

### MacroDroid payload
```json
{ "secret": "YOUR_PASSWORD", "appName": "WhatsApp", "title": "Jordan", "message": "Hey!", "type": "MESSAGE" }
```
`type` is `CALL` or `MESSAGE` (anything else becomes `MESSAGE`).

## Setup
1. Run the SQL in the spec (table + realtime) or simply run `supabase/schema.sql` (table, constraint, index, RLS, realtime).
2. Vercel -> Import repo -> set env vars from `.env.example`
   (`APP_PASSWORD`, `SUPABASE_URL`, `SUPABASE_KEY` = **service role** key, optional `ALLOWED_ORIGIN`).
3. Deploy, then point MacroDroid's HTTP POST (Content-Type `application/json`) to `https://<project>.vercel.app/api/notify`.

Local: `npm i && cp .env.example .env.local && npm run dev`.
