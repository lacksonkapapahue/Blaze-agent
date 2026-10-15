# Blaze Gateway changelog

## 0.3.0 (2026-10-15)

- **Waiting list.** A Blaze account whose email isn't approved is recorded (email, name, first /
  last seen, tries) and refused with `403 waitlisted`: "You're on the Blaze waiting list. You'll
  get access as soon as you're approved." Rejected accounts get `403 access_rejected`. Applies to
  `/v1/chat/completions` and `/v1/models`, before the quota (refused requests aren't counted).
  `ADMIN_EMAILS` accounts are always allowed.
- `/admin › Waiting list`: pending accounts with Approve (choose a plan, basic by default) /
  Reject; approved and rejected lists with Undo (back to pending); "Require approval for new
  accounts" switch (on by default; off lets new and pending accounts in, rejected stay blocked).
  Assigning a plan in Plans also approves the account.
- Migration `0003_waitlist.sql` (`settings`, `access`, `access_legacy`): grandfathers every account
  with a plan assignment, and every account with usage history (kept by user id, turned into an
  approved row on its next request). Without the migration the gateway behaves like 0.2.x and
  /admin warns.
- Fix: plans other than the one shown could look impossible to assign. Assigning the default plan
  (basic) deleted the row, so the account never appeared; the plan dropdown was filled only once,
  so a plan added later never showed up; and a failure only flashed a toast. Now every plan,
  basic included, is stored as an explicit row; the dropdown is rebuilt on every load; the list is
  grouped by plan with a plan picker and Remove per account; success and errors show in the card.
- New admin API: `POST /admin/api/access`, `PUT /admin/api/settings`; `PUT /admin/api/assignments`
  with `plan: ""` removes an assignment.

## 0.2.2 (2026-10-14)

- `blaze_avoid` (app v0.26.2): up to 8 "provider/model" routes the app just got an empty (reasoning-only)
  reply from, or whose stream broke mid-reply. They go last in that request's lineup, so the next model
  answers; avoiding every route still answers. Stripped before the upstream call like every `blaze_*` field.

## 0.2.1 (2026-10-14)

- Model picker: the native dropdown (square OS popup) is replaced by a rounded, searchable list
  grouped by provider, opened with "+ Add a model"; shows context size and a ✓ for models already
  in the list.
- No duplicate names: aliases are named from their own ids (Mistral reports its canonical id as
  the name of every alias), and same-name models of one provider get distinct names.
- Drops Gemini image-generation ("Nano Banana") and Antigravity agent models from discovery.
- Higher-contrast labels, id chips, placeholders; wider provider name field.

## 0.2.0 (2026-10-14)

- Model discovery: every provider's own model-list API (Gemini v1beta/models; OpenAI-style
  `/models` for Groq, Cerebras, Mistral, OpenRouter, NVIDIA, GitHub Models; Workers AI through the
  AI binding catalogue, curated list as fallback), cached in D1 (`migrations/0002_models.sql`:
  `models`, `discovery`). Runs after a key is added, on "Find models", and from a cron (every
  30 min, refreshing lists about a day old).
- Free only: OpenRouter `:free` models only; known paid-only models are hidden, refused when
  saving a route, and skipped at request time.
- Route editor without memorised ids: plain-language task descriptions, friendly names,
  searchable provider-grouped dropdowns filtered by capability (Vision only offers models that
  see images), up / down / remove, raw ids behind a detail, "Suggest best free lineup", Save all.
  The text editor stays under "advanced".
- Fix: "Add key" now re-renders the provider card (the focused button used to block the
  refresh, so the key looked unsaved and got added again); messages show inside the card; the
  same key added twice is ignored (compared after decryption; same last 4 within a minute when a
  row can't be decrypted).
- Admin look: rounded everywhere, orange + blue accents.
- New admin API: `GET /admin/api/models`, `POST /admin/api/providers/:id/discover`,
  `POST /admin/api/discover`, `POST /admin/api/routes/suggest`. Existing API, `task_routes` and
  routing are unchanged.

## 0.1.0 (2026-10-13)

First version.

- OpenAI-compatible `POST /v1/chat/completions` (streaming and not) and `GET /v1/models`,
  authenticated with the user's Blaze account session (`bz_…`), checked against blaze-auth's KV
  (`AUTH_KV`) or `GET /v1/me` through the `AUTH` service binding.
- Task routing: `blaze_task` tag (`chat`, `quick`, `code`, `vision`, `long`, `agent`) or
  `blaze-<task>` model name, otherwise inferred (images, tools, input length, code cues).
- Ordered model list per task with failover across providers: cooldowns with TTLs in D1
  (429 / 5xx / timeouts / gone models / provider-wide caps), demotion of cooled-down failures,
  per-key cooldown on rejected keys, key rotation, up to 6 attempts / 100 s.
- Adapters: OpenAI-compatible (Gemini, Groq, Cerebras, OpenRouter, Mistral, NVIDIA; GitHub
  Models seeded disabled, retired 2026-07-30) and Cloudflare Workers AI via the AI binding as the
  last-resort fallback (stream + tool calls translated to the OpenAI shape).
- Plans basic ($0), standard ($10), pro ($20) with configurable daily message and token quotas;
  new accounts on basic; plans assignable by email. No billing yet.
- `/admin`: Blaze-account (ADMIN_EMAILS) or ADMIN_TOKEN sign-in; providers and several keys each
  (AES-256-GCM at rest with KEY_ENCRYPTION_SECRET, shown as last 4 only); per-task model order;
  plan quotas; live health with test buttons.
- No message content logged or stored.
- CI: `.github/workflows/gateway.yml` tests every change and deploys from `main` when
  `CLOUDFLARE_API_TOKEN` is set.
