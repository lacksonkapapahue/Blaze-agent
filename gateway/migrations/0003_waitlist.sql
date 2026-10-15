-- Blaze Gateway 0.3.0: waiting list. A Blaze account whose email isn't approved here is refused
-- (403 waitlisted) and listed as pending in /admin until an admin approves or rejects it.

-- Gateway-wide switches. require_approval: '1' (default) = new accounts wait for approval.
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO settings (key, value, updated_at) VALUES ('require_approval', '1', 0);

-- One row per Blaze account email that asked for access (or was approved by an admin).
CREATE TABLE IF NOT EXISTS access (
  email TEXT PRIMARY KEY,                       -- lower-case Blaze account email
  status TEXT NOT NULL DEFAULT 'pending',       -- pending | approved | rejected
  uid TEXT NOT NULL DEFAULT '',                 -- Blaze user id (u_…) of the last request, if any
  name TEXT NOT NULL DEFAULT '',                -- account name from blaze-auth, if it has one
  first_seen INTEGER NOT NULL DEFAULT 0,        -- ms epoch, first refused/recorded request
  last_seen INTEGER NOT NULL DEFAULT 0,
  count INTEGER NOT NULL DEFAULT 0,             -- requests made while not approved
  decided_at INTEGER NOT NULL DEFAULT 0,
  decided_by TEXT NOT NULL DEFAULT ''           -- admin email, 'admin-token', 'migration' or 'usage history'
);
CREATE INDEX IF NOT EXISTS access_status ON access(status);

-- Accounts that used the gateway before 0.3.0. usage is keyed by Blaze user id (no email), so they
-- are kept here and turned into an approved access row (with email) on their next request.
CREATE TABLE IF NOT EXISTS access_legacy (
  uid TEXT PRIMARY KEY
);

-- Grandfathering: every account with a plan assignment or any usage history stays allowed.
INSERT OR IGNORE INTO access (email, status, first_seen, last_seen, decided_at, decided_by)
  SELECT email, 'approved', updated_at, updated_at, CAST(strftime('%s', 'now') AS INTEGER) * 1000, 'migration' FROM plan_assignments;
INSERT OR IGNORE INTO access_legacy (uid) SELECT DISTINCT uid FROM usage;
