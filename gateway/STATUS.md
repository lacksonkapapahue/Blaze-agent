# Blaze Gateway status

- **2026-10-15, v0.3.0 (branch `gateway-waitlist-v030`, PR open, not merged / not deployed):**
  waiting list (403 `waitlisted` / `access_rejected`, /admin › Waiting list, "Require approval"
  on by default) and the plan-assignment fix (explicit rows for every plan, dropdown rebuilt on
  every load, grouped list, visible errors). Needs migration `0003_waitlist.sql` applied before
  the Worker is deployed (merging to `main` does both through CI). The 4 accounts currently on pro
  keep access through the migration. App side: PlainError / Cloud show the waiting-list wording
  instead of "sign in again" (ships with the next app release).

- **2026-10-14, v0.2.1:** picker / naming polish after the first live discovery (gemini 29,
  mistral 27, workers-ai 35 free chat models found via "Find models", Workers AI from the binding
  catalogue). Next: add Groq / Cerebras / OpenRouter keys (discovery runs on add), then press
  "Suggest best free lineup" in Routes, review, Save all.

- **2026-10-14, v0.2.0 (branch `gateway-models`):** model discovery (D1 `models` / `discovery`,
  migration 0002, cron `*/30` refreshing day-old lists), free-only rule (OpenRouter `:free`,
  paid-only models never routed), friendly route editor with capability filters and "Suggest
  best free lineup", Add-key re-render / in-card messages / duplicate-key guard. CI now deploys
  from `main` (the Cloudflare secrets are set; the 2026-10-13 23:28 CAT workflow run deployed).
  Live at `https://ai.blaze-app.workers.dev` and `https://blaze-gateway.blaze-app.workers.dev`.
  Keys in D1: gemini, mistral (keep mistral key id 2; duplicates 3-7 were deleted by Luke).
  - Watch: OpenRouter's `/models` JSON is ~1 MB; on the Workers Free 10 ms CPU limit its
    discovery may hit the CPU cap once a key is added. If so, the error shows on the card and
    the other providers are unaffected.
  - Gemini free/paid flags are a name heuristic (Gemini 3 Pro, `-pro-preview`, deep-research
    → paid). Re-check against ai.google.dev/pricing when Google changes tiers.

- **Now:** v0.1.0 in `gateway/` of Blaze-agent (Luke's GitHub connector can't create new repos,
  so it lives here; it has its own workflow `.github/workflows/gateway.yml`, and the Android
  `build.yml` ignores `gateway/**`).
- **Deployed:** 2026-10-13 (v0.1.0, Worker version 193d76d6-42ee-4ebe-87ca-cbf1634b8448), from the
  sandbox with wrangler using a Cloudflare API token "blaze-gateway-deploy" (Workers Scripts Edit,
  D1 Edit, Workers AI Read, Workers KV Storage Read, Account Settings Read; account
  ef895b6795ee39066c863c39a42ec8b6).
  - URL: `https://blaze-gateway.blaze-app.workers.dev` · health `/health` → `{"ok":true,...}` ·
    admin `/admin` (renders; HEALTH / ROUTES / PROVIDERS / PLANS).
  - D1 `blaze-gateway` = `f27cf26b-799b-418b-8298-913c7515fe7e`, migration 0001 applied.
  - Worker secrets set: `KEY_ENCRYPTION_SECRET`, `ADMIN_EMAILS` (kapapaluckson75@gmail.com),
    `ADMIN_TOKEN`. KEY_ENCRYPTION_SECRET and ADMIN_TOKEN are saved in Luke's Hark vault.
  - Workers AI (keyless) tested OK from /admin (llama-3.3-70b fp8-fast, llama-3.1-8b fast).
    `/v1` answers 401 without a Blaze `bz_` token, as intended; an end-to-end `/v1` chat still
    needs a Blaze session token.
- **CI auto-deploy is still off:** the GitHub Actions secrets aren't set, so the Gateway
  workflow's deploy job skips. If they are added later, `KEY_ENCRYPTION_SECRET` in GitHub MUST be
  the same value as the one in the Worker (from the vault), or CI would overwrite it and saved
  provider keys would become unreadable.
- **Needs Luke:** add provider keys (Groq, Gemini, Cerebras, OpenRouter, Mistral) in
  `/admin › Providers`, then test models in `/admin › Health`.
- **Next steps (in order):**
  1. Add keys, test every model in /admin › Health, fix model ids that 404
     (ids follow each provider's catalogue and drift).
  2. Android: point "Auto" at the gateway when signed in to Blaze (base URL
     `https://blaze-gateway.blaze-app.workers.dev/v1`, key = Blaze session, send `blaze_task`
     from `ViewRouter`). Separate PR, after the chat-UI work lands.
  3. Desktop / CLI: same switch in the Go core.
  4. Gateway: per-user requests/minute burst limit; geo rule for Gemini free (no EEA/UK/CH);
     model catalogue refresh from each provider's `/models` (like Android's ProviderCatalog);
     billing hook for standard/pro.
- **Hard rules:** free tiers only; never log message content; never change KEY_ENCRYPTION_SECRET
  without re-adding keys; don't touch Android signing/keystore from here.
