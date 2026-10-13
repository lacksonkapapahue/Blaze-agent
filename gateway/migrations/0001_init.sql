-- Blaze Gateway 0.1.0 schema (Cloudflare D1, free tier).
-- No table ever holds message content. Provider keys are AES-GCM ciphertext (see src/crypto.ts).

CREATE TABLE IF NOT EXISTS providers (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  base_url TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT 'openai',          -- 'openai' (OpenAI-compatible) | 'workers-ai'
  enabled INTEGER NOT NULL DEFAULT 1,
  notes TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS provider_keys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  provider_id TEXT NOT NULL,
  label TEXT NOT NULL DEFAULT '',
  enc TEXT NOT NULL,                            -- "v1." + base64(nonce || ciphertext || tag)
  last4 TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  cooling_until INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  last_ok_at INTEGER NOT NULL DEFAULT 0,
  last_fail_at INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS provider_keys_provider ON provider_keys(provider_id);

-- Ordered model list per task tag: JSON [{"provider":"groq","model":"llama-3.3-70b-versatile"}, ...]
CREATE TABLE IF NOT EXISTS task_routes (
  task TEXT PRIMARY KEY,
  models TEXT NOT NULL,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS plans (
  id TEXT PRIMARY KEY,                          -- basic | standard | pro
  price_usd REAL NOT NULL DEFAULT 0,            -- display only, no billing yet
  daily_requests INTEGER NOT NULL,
  daily_tokens INTEGER NOT NULL DEFAULT 0,      -- 0 = no token cap
  sort INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);

-- Accounts not listed here are on the default plan (basic).
CREATE TABLE IF NOT EXISTS plan_assignments (
  email TEXT PRIMARY KEY,                       -- lower-case Blaze account email
  plan TEXT NOT NULL,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS usage (
  uid TEXT NOT NULL,                            -- Blaze user id (u_…), never the email
  day TEXT NOT NULL,                            -- UTC YYYY-MM-DD
  requests INTEGER NOT NULL DEFAULT 0,
  tokens INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uid, day)
);

-- Cooldowns: model '*' = the whole provider. "until" is the TTL (ms epoch).
CREATE TABLE IF NOT EXISTS model_state (
  provider_id TEXT NOT NULL,
  model TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'ok',
  until INTEGER NOT NULL DEFAULT 0,
  reason TEXT NOT NULL DEFAULT '',
  ok_at INTEGER NOT NULL DEFAULT 0,
  fail_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (provider_id, model)
);

CREATE TABLE IF NOT EXISTS model_stats (
  day TEXT NOT NULL,
  provider_id TEXT NOT NULL,
  model TEXT NOT NULL,
  ok INTEGER NOT NULL DEFAULT 0,
  fail INTEGER NOT NULL DEFAULT 0,
  ms_total INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, provider_id, model)
);
