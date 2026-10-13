# Blaze Gateway changelog

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
