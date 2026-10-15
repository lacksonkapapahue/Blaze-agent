// D1 access: configuration (cached per isolate for a few seconds), model health and stats.

import { DEFAULT_PLANS, DEFAULT_PROVIDERS, DEFAULT_ROUTES, type Candidate, type PlanLimits, type ProviderType } from "./defaults";
import { TASKS, type Mark, type Task } from "./router";
import { utcDay } from "./util";

export interface ProviderRow {
  id: string;
  name: string;
  base_url: string;
  type: ProviderType;
  enabled: boolean;
  notes: string;
}

export interface KeyRow {
  id: number;
  provider_id: string;
  label: string;
  enc: string;
  last4: string;
  enabled: boolean;
  cooling_until: number;
  last_error: string;
  last_ok_at: number;
  last_fail_at: number;
}

export interface Config {
  providers: Map<string, ProviderRow>;
  keys: KeyRow[];
  routes: Record<Task, Candidate[]>;
  plans: PlanLimits[];
  assignments: Map<string, string>;
  /** "provider|model" of discovered models flagged paid-only: never routed to. */
  paid: Set<string>;
  /** Gateway switches from the D1 `settings` table (migration 0003). */
  settings: Settings;
}

export interface Settings {
  /** New accounts wait for approval in /admin before they get model answers. Default on. */
  require_approval: boolean;
}

export const DEFAULT_SETTINGS: Settings = { require_approval: true };

export function parseSettings(rows: { key: string; value: string }[]): Settings {
  const s: Settings = { ...DEFAULT_SETTINGS };
  for (const r of rows) if (r.key === "require_approval") s.require_approval = r.value !== "0";
  return s;
}

const CONFIG_TTL_MS = 15_000;
let cached: { at: number; db: D1Database; cfg: Config } | null = null;
let seededFor: D1Database | null = null;

/** Drop the isolate cache (after an admin change, and in tests). */
export function invalidateConfig(): void {
  cached = null;
}

export function resetDbCacheForTests(): void {
  cached = null;
  seededFor = null;
}

/** Inserts the defaults that are missing (INSERT OR IGNORE: never overwrites admin edits). */
export async function ensureSeeded(db: D1Database): Promise<void> {
  if (seededFor === db) return;
  const n = await db.prepare("SELECT COUNT(*) AS n FROM plans").first<{ n: number }>();
  if (!n || n.n === 0) await seed(db);
  seededFor = db;
}

export async function seed(db: D1Database): Promise<void> {
  const t = Date.now();
  const stmts: D1PreparedStatement[] = [];
  for (const p of DEFAULT_PROVIDERS) {
    stmts.push(db.prepare("INSERT OR IGNORE INTO providers (id, name, base_url, type, enabled, notes, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)")
      .bind(p.id, p.name, p.base_url, p.type, p.enabled ? 1 : 0, p.notes, t));
  }
  for (const task of TASKS) {
    stmts.push(db.prepare("INSERT OR IGNORE INTO task_routes (task, models, updated_at) VALUES (?1, ?2, ?3)").bind(task, JSON.stringify(DEFAULT_ROUTES[task]), t));
  }
  DEFAULT_PLANS.forEach((p, i) => {
    stmts.push(db.prepare("INSERT OR IGNORE INTO plans (id, price_usd, daily_requests, daily_tokens, sort, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
      .bind(p.id, p.price_usd, p.daily_requests, p.daily_tokens, i, t));
  });
  await db.batch(stmts);
}

function parseRoute(s: string): Candidate[] {
  try {
    const v = JSON.parse(s);
    if (!Array.isArray(v)) return [];
    return v.filter(x => x && typeof x.provider === "string" && typeof x.model === "string").map(x => ({ provider: x.provider, model: x.model }));
  } catch {
    return [];
  }
}

export async function loadConfig(db: D1Database, now = Date.now()): Promise<Config> {
  if (cached && cached.db === db && now - cached.at < CONFIG_TTL_MS) return cached.cfg;
  await ensureSeeded(db);
  const [p, k, r, pl, a] = await db.batch([
    db.prepare("SELECT id, name, base_url, type, enabled, notes FROM providers"),
    db.prepare("SELECT id, provider_id, label, enc, last4, enabled, cooling_until, last_error, last_ok_at, last_fail_at FROM provider_keys ORDER BY id"),
    db.prepare("SELECT task, models FROM task_routes"),
    db.prepare("SELECT id, price_usd, daily_requests, daily_tokens FROM plans ORDER BY sort, id"),
    db.prepare("SELECT email, plan FROM plan_assignments"),
  ]);
  const providers = new Map<string, ProviderRow>();
  for (const x of (p!.results as any[])) providers.set(x.id, { ...x, enabled: !!x.enabled });
  const keys = (k!.results as any[]).map(x => ({ ...x, enabled: !!x.enabled })) as KeyRow[];
  const routes = {} as Record<Task, Candidate[]>;
  for (const t of TASKS) routes[t] = [];
  for (const x of (r!.results as any[])) if ((TASKS as readonly string[]).includes(x.task)) routes[x.task as Task] = parseRoute(x.models);
  const plans = (pl!.results as any[]).map(x => ({ id: x.id, price_usd: Number(x.price_usd), daily_requests: Number(x.daily_requests), daily_tokens: Number(x.daily_tokens) }));
  const assignments = new Map<string, string>();
  for (const x of (a!.results as any[])) assignments.set(x.email, x.plan);
  const paid = new Set<string>();
  // Separate from the batch so a database without migration 0002 still routes.
  try {
    const pr = await db.prepare("SELECT provider_id, model_id FROM models WHERE free = 0").all<any>();
    for (const x of pr.results) paid.add(`${x.provider_id}|${x.model_id}`);
  } catch { /* no models table yet */ }
  let settings = { ...DEFAULT_SETTINGS };
  try {
    settings = parseSettings((await db.prepare("SELECT key, value FROM settings").all<{ key: string; value: string }>()).results);
  } catch { /* no settings table yet (migration 0003 not applied): approval stays on */ }
  const cfg: Config = { providers, keys, routes, plans, assignments, paid, settings };
  cached = { at: now, db, cfg };
  return cfg;
}

// ---------------- model health ----------------

/** Every mark that still matters: cooling, or a hard failure inside the demotion window. */
export async function loadMarks(db: D1Database, now = Date.now()): Promise<Map<string, Mark>> {
  const rows = await db.prepare("SELECT provider_id, model, state, until, reason, ok_at, fail_at FROM model_state WHERE until > ?1 OR (state = 'failed' AND fail_at > ?2)")
    .bind(now, now - 6 * 3600_000).all<any>();
  const m = new Map<string, Mark>();
  for (const x of rows.results) m.set(`${x.provider_id}|${x.model}`, { state: x.state, until: x.until, reason: x.reason, okAt: x.ok_at, failAt: x.fail_at });
  return m;
}

export function saveMarkStmt(db: D1Database, key: string, mark: Mark): D1PreparedStatement {
  const i = key.indexOf("|");
  return db.prepare(
    "INSERT INTO model_state (provider_id, model, state, until, reason, ok_at, fail_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
    "ON CONFLICT (provider_id, model) DO UPDATE SET state = ?3, until = ?4, reason = ?5, ok_at = ?6, fail_at = ?7",
  ).bind(key.slice(0, i), key.slice(i + 1), mark.state, mark.until, mark.reason.slice(0, 140), mark.okAt, mark.failAt);
}

export function statStmt(db: D1Database, c: Candidate, ok: boolean, ms: number, now = Date.now()): D1PreparedStatement {
  return db.prepare(
    "INSERT INTO model_stats (day, provider_id, model, ok, fail, ms_total) VALUES (?1, ?2, ?3, ?4, ?5, ?6) " +
    "ON CONFLICT (day, provider_id, model) DO UPDATE SET ok = ok + ?4, fail = fail + ?5, ms_total = ms_total + ?6",
  ).bind(utcDay(now), c.provider, c.model, ok ? 1 : 0, ok ? 0 : 1, ok ? Math.round(ms) : 0);
}

export function keyResultStmt(db: D1Database, keyId: number, ok: boolean, now: number, coolUntil = 0, error = ""): D1PreparedStatement {
  return ok
    ? db.prepare("UPDATE provider_keys SET last_ok_at = ?2, cooling_until = 0, last_error = '' WHERE id = ?1").bind(keyId, now)
    : db.prepare("UPDATE provider_keys SET last_fail_at = ?2, cooling_until = MAX(cooling_until, ?3), last_error = ?4 WHERE id = ?1").bind(keyId, now, coolUntil, error.slice(0, 140));
}
