# Blaze Gateway status

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
