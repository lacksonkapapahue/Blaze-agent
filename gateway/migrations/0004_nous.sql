-- Blaze Gateway 0.3.1: Nous Research (Nous Portal) as a provider, and the model test log.
-- Additive only. The provider row is INSERT OR IGNORE, so an admin's edits are never overwritten
-- (fresh databases also get it from src/defaults.ts on first seed).

INSERT OR IGNORE INTO providers (id, name, base_url, type, enabled, notes, updated_at) VALUES (
  'nous', 'Nous Research (Nous Portal)', 'https://inference-api.nousresearch.com/v1', 'openai', 1,
  'Key: portal.nousresearch.com → API keys (sk-nous-…; the free plan is enough). Free models only (price 0 on the live list, which rotates). A Nous model is routed only after it passes Test and replies within 3 s.',
  0
);

-- Last /admin "Test" result per model (POST /admin/api/test-model): a real 1-line completion.
-- Providers in TEST_FIRST (Nous) are routed only when ok = 1 and ms <= 3000.
CREATE TABLE IF NOT EXISTS model_tests (
  provider_id TEXT NOT NULL,
  model_id TEXT NOT NULL,
  ok INTEGER NOT NULL DEFAULT 0,                -- 1 = 2xx with a completion
  ms INTEGER NOT NULL DEFAULT 0,                -- full reply time
  status INTEGER NOT NULL DEFAULT 0,
  kind TEXT NOT NULL DEFAULT '',                -- classify() kind on failure
  tested_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (provider_id, model_id)
);
