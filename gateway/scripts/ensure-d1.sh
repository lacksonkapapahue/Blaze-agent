#!/usr/bin/env bash
# Finds the "blaze-gateway" D1 database (creating it on the first run) and writes its id into
# wrangler.toml for this checkout. Used by CI and by first-deploy.sh. Needs CLOUDFLARE_API_TOKEN
# (and CLOUDFLARE_ACCOUNT_ID if the token can see more than one account).
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=blaze-gateway

find_id() {
  npx wrangler d1 list --json 2>/dev/null | node -e '
    let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
      try { const db = JSON.parse(s).find(d => d.name === process.argv[1]); if (db) console.log(db.uuid || db.id || ""); } catch {}
    });' "$NAME"
}

ID=$(find_id || true)
if [ -z "$ID" ]; then
  echo "Creating D1 database $NAME"
  npx wrangler d1 create "$NAME" >/dev/null
  ID=$(find_id || true)
fi
if [ -z "$ID" ]; then
  echo "Couldn't find or create the D1 database '$NAME' (does the token have D1:Edit?)" >&2
  exit 1
fi
sed -i.bak -E "s/^database_id = \"[^\"]*\"/database_id = \"$ID\"/" wrangler.toml && rm -f wrangler.toml.bak
echo "D1 $NAME = $ID"
