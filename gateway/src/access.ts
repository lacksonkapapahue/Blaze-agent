// Waiting list (0.3.0). Every app request from a Blaze account goes through requireAccess:
//   - ADMIN_EMAILS accounts: always allowed;
//   - access row 'approved': allowed;  'rejected': 403 access_rejected (even with approval off);
//   - no row / 'pending': allowed only when "Require approval for new accounts" is off; otherwise
//     the request is recorded (first/last seen, count, name) and refused with 403 waitlisted;
//   - accounts that used the gateway before 0.3.0 (access_legacy, by user id) are approved on
//     their next request.
// One D1 read per request; a write only for accounts that aren't approved.

import { adminEmails, type BlazeUser } from "./auth";
import type { Env } from "./env";
import { HttpError } from "./util";

export type AccessStatus = "pending" | "approved" | "rejected";

export const WAITLISTED_MESSAGE = "You're on the Blaze waiting list. You'll get access as soon as you're approved.";
export const REJECTED_MESSAGE = "Your Blaze account hasn't been approved for Blaze AI. Contact the Blaze team if you think this is a mistake.";

export interface AccessCheck {
  status: AccessStatus | null;
  legacy: boolean;
  requireApproval: boolean;
}

/** Pure: what to do with a request, given what D1 says. */
export function decide(c: AccessCheck, isAdmin: boolean): "allow" | "allow_legacy" | "waitlisted" | "rejected" {
  if (isAdmin) return "allow";
  if (c.status === "approved") return "allow";
  if (c.status === "rejected") return "rejected";
  if (c.legacy) return "allow_legacy";
  if (!c.requireApproval) return "allow";
  return "waitlisted";
}

export async function readRequireApproval(db: D1Database): Promise<boolean> {
  try {
    const r = await db.prepare("SELECT value FROM settings WHERE key = 'require_approval'").first<{ value: string }>();
    return r ? r.value !== "0" : true;
  } catch {
    return false; // migration 0003 not applied yet: behave like 0.2.x (everyone allowed)
  }
}

async function check(db: D1Database, user: BlazeUser): Promise<AccessCheck | null> {
  try {
    const r = await db.prepare(
      "SELECT (SELECT status FROM access WHERE email = ?1) AS status, " +
      "(SELECT 1 FROM access_legacy WHERE uid = ?2) AS legacy, " +
      "(SELECT value FROM settings WHERE key = 'require_approval') AS req",
    ).bind(user.email, user.id).first<{ status: string | null; legacy: number | null; req: string | null }>();
    const status = r?.status === "approved" || r?.status === "rejected" || r?.status === "pending" ? r.status : null;
    return { status, legacy: !!r?.legacy, requireApproval: r?.req == null ? true : r.req !== "0" };
  } catch {
    return null; // tables missing (migration 0003 not applied): don't lock everyone out
  }
}

/** Records a request from an account that isn't approved (keeps the status it already has). */
function recordStmt(db: D1Database, user: BlazeUser, now: number): D1PreparedStatement {
  return db.prepare(
    "INSERT INTO access (email, status, uid, name, first_seen, last_seen, count) VALUES (?1, 'pending', ?2, ?3, ?4, ?4, 1) " +
    "ON CONFLICT (email) DO UPDATE SET uid = ?2, name = CASE WHEN ?3 <> '' THEN ?3 ELSE name END, last_seen = ?4, count = count + 1",
  ).bind(user.email, user.id, (user.name || "").slice(0, 120), now);
}

/** Throws 403 waitlisted / access_rejected unless [user] may use the gateway. */
export async function requireAccess(env: Env, user: BlazeUser, now = Date.now()): Promise<void> {
  const isAdmin = !!user.email && adminEmails(env).includes(user.email);
  if (isAdmin) return;
  if (!user.email) throw new HttpError(403, "waitlisted", WAITLISTED_MESSAGE); // can't list an account without an email
  const c = await check(env.DB, user);
  if (!c) return;
  const d = decide(c, false);
  if (d === "allow") return;
  if (d === "allow_legacy") {
    await env.DB.prepare(
      "INSERT INTO access (email, status, uid, name, first_seen, last_seen, count, decided_at, decided_by) VALUES (?1, 'approved', ?2, ?3, ?4, ?4, 0, ?4, 'usage history') " +
      "ON CONFLICT (email) DO UPDATE SET status = 'approved', uid = ?2, decided_at = ?4, decided_by = 'usage history'",
    ).bind(user.email, user.id, (user.name || "").slice(0, 120), now).run().catch(() => undefined);
    return;
  }
  await recordStmt(env.DB, user, now).run().catch(() => undefined);
  if (d === "rejected") throw new HttpError(403, "access_rejected", REJECTED_MESSAGE);
  throw new HttpError(403, "waitlisted", WAITLISTED_MESSAGE);
}

// ---------------- admin side ----------------

export interface AccessRow {
  email: string;
  status: AccessStatus;
  name: string;
  first_seen: number;
  last_seen: number;
  count: number;
  decided_at: number;
  decided_by: string;
}

export async function loadAccess(db: D1Database): Promise<{ ok: boolean; require_approval: boolean; rows: AccessRow[]; legacy: number }> {
  try {
    const [rows, legacy, req] = await db.batch([
      db.prepare("SELECT email, status, name, first_seen, last_seen, count, decided_at, decided_by FROM access ORDER BY CASE status WHEN 'pending' THEN last_seen ELSE decided_at END DESC, email"),
      db.prepare("SELECT COUNT(*) AS n FROM access_legacy l WHERE NOT EXISTS (SELECT 1 FROM access a WHERE a.uid = l.uid)"),
      db.prepare("SELECT value FROM settings WHERE key = 'require_approval'"),
    ]);
    const rv = (req!.results as any[])[0]?.value;
    return { ok: true, rows: rows!.results as AccessRow[], legacy: Number((legacy!.results as any[])[0]?.n ?? 0), require_approval: rv == null ? true : rv !== "0" };
  } catch {
    return { ok: false, rows: [], legacy: 0, require_approval: false };
  }
}

/** Sets an account's status (admin decision). Creates the row when the account never asked. */
export function setStatusStmt(db: D1Database, email: string, status: AccessStatus, by: string, now: number): D1PreparedStatement {
  return db.prepare(
    "INSERT INTO access (email, status, first_seen, last_seen, count, decided_at, decided_by) VALUES (?1, ?2, 0, 0, 0, ?3, ?4) " +
    "ON CONFLICT (email) DO UPDATE SET status = ?2, decided_at = ?3, decided_by = ?4",
  ).bind(email, status, now, by.slice(0, 254));
}
