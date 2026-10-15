# Blaze Gateway 0.2.1

One OpenAI-compatible endpoint for every Blaze app, so users never set up models or API keys.
A Cloudflare Worker holds Blaze's own free-tier provider keys, picks the best model for each
request's task, and fails over across providers when one is rate-limited or down. Luke manages
providers, keys, model order, plan quotas and health from `/admin`.

- Will deploy to: `https://blaze-gateway.blaze-app.workers.dev` (`GET /health`)
- Admin: `https://blaze-gateway.blaze-app.workers.dev/admin`
- Free tier only: one Worker, one D1 database, Workers AI's free 10,000 Neurons/day, plus each
  provider's free plan. Same Cloudflare account as blaze-auth (`ef895b6795ee39066c863c39a42ec8b6`).
- First-time setup: **[SETUP.md](SETUP.md)**. Provider terms: **[PROVIDER-TERMS.md](PROVIDER-TERMS.md)**.

## For app developers

```http
POST /v1/chat/completions
Authorization: Bearer bz_…            ← the user's Blaze account session token (blaze-auth)
Content-Type: application/json

{"model": "blaze-auto", "blaze_task": "code", "stream": true,
 "messages": [{"role": "user", "content": "Write a Go HTTP server"}]}
```

- Any OpenAI SDK works: base URL `https://blaze-gateway.blaze-app.workers.dev/v1`, API key = the
  Blaze session token.
- `blaze_task` (optional): `chat`, `quick`, `code`, `vision`, `long`, `agent` (`tools` and
  `agent/tools` also work). Or pick it with the model name: `blaze-code`, `blaze-vision`, …
- No tag: the gateway infers it. Images → `vision`; `tools` present → `agent`; more than
  ~48,000 characters → `long`; code in the last message → `code`; otherwise `chat`.
- `GET /v1/models` lists `blaze-auto` plus one model per task.
- Response headers say what answered: `X-Blaze-Task` (`;inferred` when guessed),
  `X-Blaze-Provider`, `X-Blaze-Model`, `X-RateLimit-Remaining-Requests`.
- Errors use the OpenAI shape `{"error":{"message","type","code"}}`:
  `401 invalid_api_key` (sign in again), `403 waitlisted` (account on the waiting list, not approved yet),
  `403 access_rejected` (an admin rejected the account), `429 quota_exceeded` (daily plan allowance; `Retry-After`
  = seconds to 00:00 UTC), `503 blaze_models_busy` (every model cooling; `Retry-After` = when the
  first frees up), `400 invalid_request_error` (two models in a row rejected the request itself).

## How routing works (mirrors Blaze Android v0.23.4 Auto)

1. Each task tag has an ordered model list (`provider` + `model`), editable in `/admin › Routes`.
   Every default list ends with Cloudflare Workers AI through the `AI` binding: no key, last resort.
2. Providers that are disabled or have no usable key are skipped; models cooling down are skipped;
   a model whose hard failure has cooled down goes to the back until it works again.
3. Up to 6 attempts and 100 s per request; 30 s to first byte per attempt. Once a provider starts
   answering, its stream is passed straight through (no failover mid-stream).
4. What a failure means (`src/router.ts › classify`), stored with a TTL in D1 `model_state`:

| Upstream says | Effect |
| --- | --- |
| 429 / quota | that model cools for the provider's `Retry-After` / `x-ratelimit-reset-*` (default 60 s, 3 s–6 h); another key of the same provider is tried first |
| 402, credits, OpenRouter free-models-per-day, Workers AI daily Neurons | the whole provider rests |
| 401 / invalid key | only that key cools (30 min); the next key is tried for the same model |
| 404 / model gone | 24 h |
| 403 | 6 h |
| 5xx | 2 min |
| timeout / network | 3 min |
| 413 / context too long | next model, nothing cooled |
| other 400 | next model, nothing cooled (one client's bad request never cools a shared model); two in a row → the 400 goes back to the client |

Keys rotate round-robin per provider. A request that no model could answer isn't charged.

## Quotas

Plans (editable in `/admin › Plans`; billing isn't wired yet, prices are labels):

| Plan | Price | Messages/day | Tokens/day |
| --- | --- | --- | --- |
| basic (every new account) | $0 | 150 | 300,000 |
| standard | $10 | 1,000 | 2,000,000 |
| pro | $20 | 5,000 | no cap |

Counted per Blaze account per UTC day in D1 `usage` (one atomic upsert per request). Tokens are
added when the provider reports `total_tokens`. Assign a plan to an account's email in
`/admin › Plans` (any plan, basic included; this also approves the account); everyone else is on basic.

## Waiting list (0.3.0)

With "Require approval for new accounts" on (the default; D1 `settings.require_approval`), a
Blaze account whose email isn't approved gets `403 waitlisted` ("You're on the Blaze waiting
list. You'll get access as soon as you're approved.") and is listed in `/admin › Waiting list`
with its name, first/last request and number of tries. Approve (picking a plan, basic by default)
or Reject; approved and rejected accounts can be moved back to pending. Rejected accounts get
`403 access_rejected`, even with approval off. `ADMIN_EMAILS` accounts are always allowed.
Migration `0003_waitlist.sql` approves every account that had a plan assignment, and every
account with usage history (by user id; it gets its email row on its next request).

## Privacy

- Message content is never logged or stored: D1 holds counters, cooldowns, provider config and
  encrypted keys only (a test dumps every table to check). Worker observability is off, and errors
  log only an error name.
- Upstream error bodies are read (first 2 KB) to classify failures, then dropped.
- The client's `user` field and every `blaze_*` field are stripped before the request goes upstream.
- Free provider tiers have their own data terms (some train on prompts): see PROVIDER-TERMS.md.

## Accounts

blaze-auth issues opaque session tokens (`bz_…`) and keeps `sess:<base64url(sha256(token))>` and
`user:<id>` (with its live session hashes) in its KV. The gateway binds that KV namespace
read-only (`AUTH_KV`) and checks a token with two KV reads, exactly like blaze-auth's own
`requireUser`; results are cached for 60 s per isolate. If `AUTH_KV` isn't bound it calls
blaze-auth `GET /v1/me` through the `AUTH` service binding (a Worker can't fetch another
`*.workers.dev` Worker on the same account over the internet).

## Admin

`/admin` is one static page (no external assets, strict CSP with a per-response nonce).

- Sign in with a Blaze account whose email is in `ADMIN_EMAILS` (the password goes to blaze-auth
  through the service binding; you get a normal Blaze session, which counts toward blaze-auth's
  20-sessions-per-account limit; Sign out ends it), or with the `ADMIN_TOKEN` secret.
- **Health**: per model: last success / failure, cooldown and why, requests today (ok / fail,
  average latency), `test` (a 5-token completion through the real path) and `clear` cooldown.
- **Routes**: per task (Chat, Quick, Code, Vision, Long, Agent) a plain-language description and an
  ordered fallback list of friendly model names ("Gemini Flash (fast, sees images)"). "+ Add a
  model" opens a searchable, rounded list grouped by provider (by default only models that fit the task: Vision shows
  only models that see images, Agent only tool-calling ones, Long only large-context ones), move
  up / down, remove; raw ids sit behind a small "id" detail. Slots are flagged when a model lacks
  what the task needs, isn't in the provider's latest list, or its provider has no key yet.
  **Suggest best free lineup** fills every task from discovered models (review, then Save all);
  Reset to defaults; "advanced: edit as text" keeps the old `provider model` lines.
- **Providers**: add / edit / disable (name, https base URL, type `openai` or `workers-ai`),
  several keys each, `test key` (`GET {base}/models`, no tokens spent), enable/disable/delete keys.
  Adding the same key twice is a no-op (compared after decryption). **Find models** runs model
  discovery for that provider; messages show inside the provider's card.

## Model discovery (0.2.0)

`src/catalog.ts` asks each provider's own model-list API and caches the chat models in D1
`models` (provider, id, friendly name, capabilities `chat/vision/code/fast/long/tools`, context
length, free flag) plus one `discovery` row per provider (when, how many, source, last error).

| Provider | List API |
| --- | --- |
| Gemini | native `GET …/v1beta/models?pageSize=1000` (`x-goog-api-key`); `generateContent` models only |
| Groq, Cerebras, Mistral, OpenRouter, NVIDIA, GitHub Models | `GET {base_url}/models` (Bearer key; OpenRouter's list is public) |
| Workers AI | the AI binding's `models({task:"Text Generation"})`; a built-in curated list if that fails |

- Embeddings, speech, image generation, moderation, guard and OCR models are dropped.
- Capabilities come from provider metadata where given (Mistral `capabilities`, OpenRouter
  `architecture` / `supported_parameters`, Workers AI properties, Gemini token limits), otherwise
  from the model id.
- **Free only:** OpenRouter keeps only `:free` models; Gemini models matching known paid-only
  names (Gemini 3 Pro, `-pro-preview`, deep-research) are stored with `free = 0`, hidden in
  `/admin`, refused by `PUT /routes/:task`, and skipped at request time even if an old route names
  them. Free flags for Gemini are a name heuristic; check Google's pricing page if in doubt.
- Runs right after a key is added, when **Find models** is pressed, and from the cron
  (`*/30 * * * *`, but each provider is refreshed only when its list is ~20 h old, failed ones
  after 6 h), so it's effectively daily. Routing never depends on the catalogue: routes whose
  model isn't in `models` keep working exactly as before.
- **Plans**: price label, messages/day, tokens/day; assign plans by email (grouped by plan).
- **Waiting list**: pending / approved / rejected accounts, the "Require approval" switch.

Keys: AES-256-GCM, key derived with HKDF-SHA256 from the `KEY_ENCRYPTION_SECRET` Worker secret,
fresh 12-byte nonce, provider id bound in as AAD; stored as `v1.<base64>`. Shown only as `••••` +
last 4. If the secret changes, old keys can't be decrypted: they're skipped and the admin page
says so; add them again.

## Endpoints

| Method | Path | Auth |
| --- | --- | --- |
| GET | `/health` | none |
| GET | `/v1/models` | Blaze token |
| POST | `/v1/chat/completions` | Blaze token |
| GET | `/admin` | none (page only) |
| POST | `/admin/api/login` | email+password or token |
| GET | `/admin/api/me`, `/admin/api/state` | admin |
| POST | `/admin/api/providers`, PUT/DELETE `/admin/api/providers/:id` | admin |
| POST | `/admin/api/providers/:id/keys`, PATCH/DELETE `/admin/api/keys/:id`, POST `/admin/api/keys/:id/test` | admin |
| PUT | `/admin/api/routes/:task`, POST `/admin/api/routes/reset` | admin |
| PUT | `/admin/api/plans/:id`, PUT `/admin/api/assignments` `{email, plan}` (`plan: ""` removes) | admin |
| POST | `/admin/api/access` `{email, status: approved\|rejected\|pending, plan?}` (waiting list) | admin |
| PUT | `/admin/api/settings` `{require_approval: boolean}` | admin |
| POST | `/admin/api/test-model`, `/admin/api/cooldowns/clear`, `/admin/api/seed`, `/admin/api/logout` | admin |
| GET | `/admin/api/models` (free discovered models + discovery status) | admin |
| POST | `/admin/api/providers/:id/discover`, `/admin/api/discover` (all) | admin |
| POST | `/admin/api/routes/suggest` `{apply?: true}` (best free lineup per task) | admin |

## Develop

```bash
cd gateway
npm ci
npx tsc --noEmit        # type check (src/)
npx vitest run          # 60 tests: router, quota, crypto, catalogue, and the Worker end to end
npx wrangler dev        # local; put KEY_ENCRYPTION_SECRET etc. in gateway/.dev.vars (git-ignored)
```

Tests run the real Worker `fetch()` against D1 on Node's built-in `node:sqlite` (the real
migrations), a fake KV with blaze-auth-shaped sessions, a fake AI binding, and a mocked network.

## Free-tier budget

Workers Free: 100,000 requests/day, 10 ms CPU per request (waiting on providers doesn't count).
D1 Free: 5 M rows read, 100,000 rows written per day; a chat costs about 3–5 row writes.
Workers AI Free: 10,000 Neurons/day (about 40k output tokens on Llama 3.3 70B, more on Llama 4
Scout), so it's only the fallback. KV reads (token checks) come from blaze-auth's namespace:
100,000/day free, cached 60 s per isolate.

## Files

`src/index.ts` routes · `src/chat.ts` quota → task → failover · `src/router.ts` tags, inference,
plan, classify (pure) · `src/providers.ts` OpenAI-compatible + Workers AI adapters ·
`src/quota.ts` plans · `src/auth.ts` Blaze tokens, admins · `src/crypto.ts` AES-GCM ·
`src/db.ts` D1 · `src/admin.ts` admin API · `src/admin-page.ts` the page ·
`src/defaults.ts` seed providers / routes / plans · `src/catalog.ts` model discovery, capabilities,
free-only rule, suggested lineups · `migrations/0001_init.sql` schema, `0002_models.sql` catalogue, `0003_waitlist.sql`
waiting list · `src/access.ts` waiting-list check.
