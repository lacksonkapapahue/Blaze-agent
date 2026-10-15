-- Blaze Gateway 0.3.0: waiting list (approval for new accounts) and gateway settings.
-- Additive only. No message content, ever.

-- Gateway-wide switches, edited in /admin. require_approval = '1': accounts not approved yet go
-- on the waiting list and get 403 waitlisted instead of model answers.
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO settings (key, value, updated_at) VALUES ('require_approval', '1', 0);

-- One row per Blaze account the gateway has seen or an admin has decided on.
-- email is the lower-case Blaze email; uid the Blaze user id (u_…). Either can be NULL for a while:
-- an email approved in /admin before that person ever signed in has no uid yet, and an account
-- grandfathered from usage (which only stores uids) has no email until its next request.
CREATE TABLE IF NOT EXISTS accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT UNIQUE,
  uid TEXT UNIQUE,
  name TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',       -- pending | approved | rejected
  first_seen INTEGER NOT NULL DEFAULT 0,        -- ms epoch, 0 = never seen (approved ahead)
  last_seen INTEGER NOT NULL DEFAULT 0,
  requests INTEGER NOT NULL DEFAULT 0,          -- gateway requests seen from this account
  decided_at INTEGER NOT NULL DEFAULT 0,
  decided_by TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS accounts_status ON accounts(status);

-- Grandfathering: nobody using Blaze today is locked out.
-- 1. every email with a plan assignment;
INSERT OR IGNORE INTO accounts (email, status, decided_at, decided_by)
  SELECT email, 'approved', CAST(strftime('%s','now') AS INTEGER) * 1000, 'migration 0003 (had a plan)' FROM plan_assignments;
-- 2. every account with usage (usage keeps uids only; the email is filled in on its next request).
INSERT OR IGNORE INTO accounts (uid, status, first_seen, decided_at, decided_by)
  SELECT uid, 'approved', 0, CAST(strftime('%s','now') AS INTEGER) * 1000, 'migration 0003 (had usage)' FROM usage GROUP BY uid;
