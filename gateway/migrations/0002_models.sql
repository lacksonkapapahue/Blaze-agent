-- Blaze Gateway 0.2.0: model catalogue, filled by discovery (each provider's own model-list API).
-- Additive only: task_routes keep their JSON [{provider, model}] lists and work without this table.

CREATE TABLE IF NOT EXISTS models (
  provider_id TEXT NOT NULL,
  model_id TEXT NOT NULL,                       -- the id sent upstream (e.g. gemini-flash-latest, @cf/meta/…)
  name TEXT NOT NULL DEFAULT '',                -- friendly name, e.g. "Gemini Flash (fast, sees images)"
  caps TEXT NOT NULL DEFAULT '[]',              -- JSON: any of chat, vision, code, fast, long, tools
  context INTEGER NOT NULL DEFAULT 0,           -- context window in tokens, 0 = unknown
  free INTEGER NOT NULL DEFAULT 1,              -- 0 = paid-only: never offered, never routed to
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (provider_id, model_id)
);

-- One row per provider: when discovery last ran, how many models, and the last error.
CREATE TABLE IF NOT EXISTS discovery (
  provider_id TEXT PRIMARY KEY,
  ran_at INTEGER NOT NULL DEFAULT 0,
  ok INTEGER NOT NULL DEFAULT 0,
  count INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL DEFAULT '',              -- api | binding | curated
  error TEXT NOT NULL DEFAULT ''
);
