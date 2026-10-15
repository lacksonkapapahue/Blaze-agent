# Blaze Gateway changelog

## 0.3.1 (2026-10-16)

- **Nous Research (Nous Portal) provider** (`nous`, OpenAI-compatible,
  `https://inference-api.nousresearch.com/v1`, `Authorization: Bearer sk-nous-…`). In the provider
  registry like Groq / NVIDIA (seeded for new databases; migration `0004_nous.sql` adds the row to
  existing ones without touching admin edits), so its key can be pasted in /admin › Providers.
  **No Nous model is in any route** (no key exists yet).
- **Free only, from the live list.** Nous's `/v1/models` is public and carries OpenRouter-style
  `pricing`; discovery keeps only models priced 0 (prompt and completion, `"0"` or
  `"0.0000000000"`) and runs before a key exists (keyless list, refreshed by the cron about daily,
  and on "Find models" / "Refresh list"). The free list rotates, so a Nous model is routed only
  while it is on the latest discovered free list.
- **Test first, reply within 3 s (Joey's rule).** For Nous, a model is routable only after
  /admin **Test** (`POST /admin/api/test-model`, a real short completion, now timed to the end of
  the reply and recorded in the new D1 table `model_tests`) answered within 3 s. Saving a route
  with an untested, slow, failed or not-free Nous model is refused with the reason; such models are
  skipped at request time and left out of "Suggest best free lineup". Picking a Nous model in the
  route picker runs Test first and only adds it when it passes.
- **Admin:** the Nous provider card shows "Free models right now" from the live list with context,
  capabilities, a **free** tag, the last Test result (✓ 1.2 s / slow 4.2 s / failed 429 / untested)
  and a Test button; the route picker flags Nous models the same way and refreshes the live list
  when it's over 10 minutes old. `GET /admin/api/models` adds `tests`, `test_first`,
  `free_list_only`, `fast_ms`; test-model answers add `fast`, `listed`, `vetted` for Nous.
- **Errors:** Nous 401 ("invalid, blocked or out of funds") cools that key for 30 min, 402 rests the
  whole provider, 429 cools the model for Retry-After (60 s default), each falling over to the next
  model, exactly like the other providers (covered by new tests).

## 0.3.0 (2026-10-15)

- **Waiting list.** Every Blaze account that reaches the gateway is recorded in D1 `accounts`
  (email, name, first / last seen, request count). With the new admin switch **Require approval
  for new accounts** (D1 `settings`, default on), accounts that aren't approved get
  `403 waitlisted` ("You're on the Blaze waiting list. You'll get access as soon as you're
  approved.") instead of model answers, and cost no quota; rejected accounts get
  `403 access_rejected`. Admins are always allowed. New admin tab **Waiting list**: pending people
  with Approve (pick a plan, the default plan preselected) / Reject, and the approved and rejected
  lists with undo. `GET /v1/models` records the account but still lists models.
- Migration `0003_access.sql` (`settings`, `accounts`) grandfathers everyone already using Blaze:
  every email with a plan assignment and every account with usage is approved, so nobody is locked
  out. A Worker running before the migration is applied behaves like 0.2.x (no lock-out).
- **Fix: assigning a plan only seemed to work for non-default plans** ("can't add emails on plans
  other than pro"). Choosing the default plan (basic, the dropdown's first and preselected option)
  deleted the email's row, so "saved" showed and the email never appeared. Now every plan, the
  default included, is stored and listed; "remove" puts an account back on the default. The plan
  dropdown is rebuilt on every render (it used to be filled once, so plans added later never
  showed), assignments are grouped by plan, and errors / confirmations show inside the card
  (e.g. "No such plan: gold", "Saved: x@y.com is on standard and approved").
  Assigning a plan also approves that email.
- New admin API: `PUT /admin/api/settings`, `POST /admin/api/access/{approve,reject,pending}`;
  `PUT /admin/api/assignments` takes `{remove: true}`; `/admin/api/state` adds `settings` and `accounts`.

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
