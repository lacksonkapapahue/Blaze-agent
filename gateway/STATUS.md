# Blaze Gateway status

- **Now:** v0.1.0 in `gateway/` of Blaze-agent (Luke's GitHub connector can't create new repos,
  so it lives here; it has its own workflow `.github/workflows/gateway.yml`, and the Android
  `build.yml` ignores `gateway/**`).
- **Deploys to:** `https://blaze-gateway.blaze-app.workers.dev` · admin `/admin`. Not deployed yet:
  waits for Luke's GitHub secrets (see SETUP.md).
- **Needs Luke:** SETUP.md steps 1–5 (Cloudflare token, KEY_ENCRYPTION_SECRET, ADMIN_EMAILS, run
  the Gateway workflow, add provider keys in /admin).
- **Next steps (in order):**
  1. After the first deploy: add keys, test every model in /admin › Health, fix model ids that 404
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
