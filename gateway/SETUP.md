# Blaze Gateway: one-time setup (Luke)

Everything is free tier. About 15 minutes. After step 3, every push to `main` that touches
`gateway/` tests and deploys by itself.

## 1. Make a Cloudflare API token

dash.cloudflare.com › My Profile › API Tokens › **Create Token** › *Custom token*:

- Account › **Workers Scripts** › Edit
- Account › **D1** › Edit
- Account › **Workers AI** › Read
- Account › **Workers KV Storage** › Read (to bind blaze-auth's namespace)
- Account resources: the Cloudflare account (the one blaze-auth runs on)

(The "Edit Cloudflare Workers" template plus D1 Edit also works.) Copy the token.

## 2. Make the encryption secret

Any long random string, kept forever (changing it makes saved provider keys unreadable):

```bash
openssl rand -base64 48
```

Save it in your password manager too.

## 3. Add the GitHub secrets

github.com/lacksonkapapahue/Blaze-agent › Settings › Secrets and variables › Actions ›
**New repository secret**:

| Name | Value |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | the token from step 1 |
| `CLOUDFLARE_ACCOUNT_ID` | the Cloudflare account's ID (dashboard › Workers & Pages, right-hand column) |
| `KEY_ENCRYPTION_SECRET` | the string from step 2 |
| `ADMIN_EMAILS` | your Blaze account email (comma-separate several) |
| `GATEWAY_ADMIN_TOKEN` | optional: 24+ random characters, a fallback admin login |

## 4. First deploy

Actions › **Gateway** › *Run workflow* on `main` (or push any change under `gateway/`). The
deploy job:

1. finds or creates the D1 database `blaze-gateway` (`scripts/ensure-d1.sh`, same as
   `npx wrangler d1 create blaze-gateway` the first time),
2. applies `migrations/` (`npx wrangler d1 migrations apply blaze-gateway --remote`),
3. `npx wrangler deploy`,
4. copies `KEY_ENCRYPTION_SECRET`, `ADMIN_EMAILS` and `GATEWAY_ADMIN_TOKEN` into the Worker's
   secrets (`wrangler secret put`),
5. checks `https://blaze-gateway.blaze-app.workers.dev/health`.

Without `CLOUDFLARE_API_TOKEN` the deploy job just says "skipping deploy" and passes.

Prefer doing it from a computer once? `cd gateway && export CLOUDFLARE_API_TOKEN=… CLOUDFLARE_ACCOUNT_ID=<the Cloudflare account ID> && ./scripts/first-deploy.sh`, then the three `npx wrangler secret put …` lines it prints.

## 5. Add provider keys

Open **https://blaze-gateway.blaze-app.workers.dev/admin**, sign in with your Blaze account
(email + password; it must be in `ADMIN_EMAILS`) or the admin token, then **Providers**:

| Provider | Get a free key | Notes |
| --- | --- | --- |
| Gemini | aistudio.google.com/apikey | best default for chat / vision / long / agent |
| Groq | console.groq.com/keys | turn on Zero Data Retention in Data Controls |
| Cerebras | cloud.cerebras.ai | free tier is officially for development only |
| OpenRouter | openrouter.ai/settings/keys | 50 free requests/day per account |
| Mistral | console.mistral.ai (free plan, phone verification) | opt out of training |

Paste each key, press **test key**, then go to **Health** and press **test** on a few models. Add
more than one key per provider to rotate them. Workers AI needs nothing.

Read [PROVIDER-TERMS.md](PROVIDER-TERMS.md) before pointing real users at it.

## 6. Check it

```bash
curl https://blaze-gateway.blaze-app.workers.dev/health
# with your Blaze session token (Blaze Desktop: ~/.blaze config; Android: Settings › Blaze account)
curl https://blaze-gateway.blaze-app.workers.dev/v1/chat/completions \
  -H "Authorization: Bearer bz_…" -H "Content-Type: application/json" \
  -d '{"model":"blaze-auto","messages":[{"role":"user","content":"Say hi"}]}' -i
```

The `X-Blaze-Provider` / `X-Blaze-Model` headers show which model answered.

## Not part of this setup

- The Android "Auto" switch to the gateway, and Desktop/CLI clients: later steps.
- Billing for standard/pro: out of scope; assign plans by email in `/admin › Plans`.
- blaze-auth itself is unchanged. The live Worker reports version 0.5.0 while
  `Blaze-dekstop/cloud` is at 0.8.0: deploying that is separate. The gateway only needs its
  sessions, which work the same in both.
