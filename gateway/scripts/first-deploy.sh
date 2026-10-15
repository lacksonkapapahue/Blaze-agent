#!/usr/bin/env bash
# One-time manual setup from a computer (CI does the same on every push to main once the GitHub
# secrets exist). Run from Blaze-agent/gateway:
#   export CLOUDFLARE_API_TOKEN=…   CLOUDFLARE_ACCOUNT_ID=<the Cloudflare account ID>
#   ./scripts/first-deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${CLOUDFLARE_API_TOKEN:?export CLOUDFLARE_API_TOKEN first (see SETUP.md)}"

npm ci
npx tsc --noEmit
npx vitest run

./scripts/ensure-d1.sh
npx wrangler d1 migrations apply blaze-gateway --remote
npx wrangler deploy

echo
echo "Now set the secrets (each prompts for the value; nothing is written to disk):"
echo "  npx wrangler secret put KEY_ENCRYPTION_SECRET   # e.g. the output of: openssl rand -base64 48"
echo "  npx wrangler secret put ADMIN_EMAILS            # your Blaze account email"
echo "  npx wrangler secret put ADMIN_TOKEN             # optional fallback, 24+ characters"
echo
echo "Then open https://blaze-gateway.blaze-app.workers.dev/admin and add provider keys."
