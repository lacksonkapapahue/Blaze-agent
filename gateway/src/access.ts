// Waiting list (0.3.0). Every Blaze account the gateway sees gets a row in `accounts`.
// With "Require approval for new accounts" on (the default), an account that isn't approved yet is
// recorded as pending and its model calls get 403 `waitlisted`; a rejected one gets 403
// `access_rejected` (whatever the switch says). Admins (ADMIN_EMAILS) are always allowed.
// Approved verdicts are cached per isolate for a minute; anything else is re-read every request.

import { adminEmails, type BlazeUser } from "./auth";
import { loadConfig } from "./db";
import type { Env } from "./env";
import { HttpError } from "./util";

export type AccessStatus = "pending" | "approved" | "rejected";

export interface AccountRow {
  id: number;
  email: string | null;
  uid: string | null;
  name: string;
  status: AccessStatus;
  first_seen: number;
  last_seen: number;
  requests: number;
  decided_at: number;
  decided_by: string;
}

export const WAITLISTED_MESSAGE = "You're on the Blaze waiting list. You'll get access as soon as you're approved.";
export const REJECTED_MESSAGE = "Your Blaze account doesn't have access to Blaze AI. If you think this is a mistake, contact the Blaze team.";

const APPROVED_TTL_MS = 60_000;
const approvedCache = new Map<string, number>(); // uid → cached until

export function clearAccessCache(): void {
  approvedCache.clear();
}

const COLS = "id, email, uid, name, status, first_seen, last_seen, requests, decided_at, decided_by";

/**
 * Finds the account row for [user], merging an email-only row (approved ahead in /admin) with a
 * uid-only row (grandfathered from usage) into one, and records this request on it.
 * Returns the row after the update, or null when there is none yet.
 */
async function touch(db: D1Database, user: BlazeUser, now: number): Promise<AccountRow | null> {
  const email = user.email || null;
  const rows = (await db.prepare(`SELECT ${COLS} FROM accounts WHERE uid = ?1 OR (?2 IS NOT NULL AND email = ?2)`).bind(user.id, email).all<AccountRow>()).results;
  if (!rows.length) return null;
  const byEmail = email ? rows.find(r => r.email === email) : undefined;
  const byUid = rows.find(r => r.uid === user.id);
  const keep = byEmail ?? byUid!;
  const stmts: D1PreparedStatement[] = [];
  if (byEmail && byUid && byEmail.id !== byUid.id) {
    // Two rows for one person: the email row is what an admin decided on; carry the counts over.
    stmts.push(db.prepare("DELETE FROM accounts WHERE id = ?1").bind(byUid.id));
    keep.requests += byUid.requests;
    keep.first_seen = keep.first_seen && byUid.first_seen ? Math.min(keep.first_seen, byUid.first_seen) : keep.first_seen || byUid.first_seen;
  }
  keep.uid = user.id;
  if (email) keep.email = email;
  if (user.name) keep.name = user.name.slice(0, 120);
  keep.first_seen = keep.first_seen || now;
  keep.last_seen = now;
  keep.requests += 1;
  stmts.push(db.prepare("UPDATE accounts SET uid = ?2, email = ?3, name = ?4, first_seen = ?5, last_seen = ?6, requests = ?7 WHERE id = ?1")
    .bind(keep.id, keep.uid, keep.email, keep.name, keep.first_seen, keep.last_seen, keep.requests));
  await db.batch(stmts);
  return keep;
}

async function insertNew(db: D1Database, user: BlazeUser, status: AccessStatus, now: number, decidedBy = ""): Promise<void> {
  await db.prepare(
    "INSERT INTO accounts (email, uid, name, status, first_seen, last_seen, requests, decided_at, decided_by) VALUES (?1, ?2, ?3, ?4, ?5, ?5, 1, ?6, ?7) " +
    "ON CONFLICT DO NOTHING",
  ).bind(user.email || null, user.id, (user.name || "").slice(0, 120), status, now, status === "pending" ? 0 : now, decidedBy).run();
}

/**
 * Records [user] and decides whether it may use models.
 * enforce = false (e.g. GET /v1/models) only records; chat completions pass true and get the 403.
 */
export async function checkAccess(env: Env, user: BlazeUser, ctx: ExecutionContext, enforce: boolean, now = Date.now()): Promise<AccessStatus> {
  const cached = approvedCache.get(user.id);
  if (cached && cached > now) {
    // Approved: keep last seen / request count fresh without holding up the answer.
    ctx.waitUntil(env.DB.prepare("UPDATE accounts SET last_seen = ?2, requests = requests + 1 WHERE uid = ?1").bind(user.id, now).run().then(() => undefined, () => undefined));
    return "approved";
  }
  const isAdmin = !!user.email && adminEmails(env).includes(user.email);
  let row: AccountRow | null;
  try {
    row = await touch(env.DB, user, now);
  } catch (e) {
    // Worker deployed before `migrations apply` ran: don't lock everyone out, run as before 0.3.0.
    if (/no such table/i.test(String((e as Error)?.message))) return "approved";
    throw e;
  }
  if (!row) {
    const cfg = await loadConfig(env.DB);
    const open = !cfg.settings.require_approval;
    if (isAdmin) await insertNew(env.DB, user, "approved", now, "admin (ADMIN_EMAILS)");
    else if (open) await insertNew(env.DB, user, "approved", now, "auto (approval was off)");
    else await insertNew(env.DB, user, "pending", now);
    row = { status: isAdmin || open ? "approved" : "pending" } as AccountRow;
  }
  let status = row.status;
  if (status !== "approved" && isAdmin) status = "approved"; // admins are never locked out
  else if (status === "pending") {
    const cfg = await loadConfig(env.DB);
    if (!cfg.settings.require_approval) {
      // Approval switched off: let waiting people in (and remember it).
      await env.DB.prepare("UPDATE accounts SET status = 'approved', decided_at = ?2, decided_by = 'auto (approval was off)' WHERE uid = ?1 AND status = 'pending'").bind(user.id, now).run();
      status = "approved";
    }
  }
  if (status === "approved") {
    if (approvedCache.size > 5000) approvedCache.clear();
    approvedCache.set(user.id, now + APPROVED_TTL_MS);
    return status;
  }
  if (!enforce) return status;
  if (status === "rejected") throw new HttpError(403, "access_rejected", REJECTED_MESSAGE);
  throw new HttpError(403, "waitlisted", WAITLISTED_MESSAGE);
}

// ---------------- admin side ----------------

export async function listAccounts(db: D1Database): Promise<AccountRow[]> {
  return (await db.prepare(`SELECT ${COLS} FROM accounts ORDER BY CASE status WHEN 'pending' THEN 0 WHEN 'approved' THEN 1 ELSE 2 END, last_seen DESC, id DESC`).all<AccountRow>()).results;
}

/** Sets the status of the account [id] (or the one with [email], created when missing). */
export async function decide(db: D1Database, target: { id?: number; email?: string }, status: AccessStatus, by: string, now = Date.now()): Promise<AccountRow> {
  let row: AccountRow | null = null;
  if (target.id) row = await db.prepare(`SELECT ${COLS} FROM accounts WHERE id = ?1`).bind(target.id).first<AccountRow>();
  else if (target.email) {
    row = await db.prepare(`SELECT ${COLS} FROM accounts WHERE email = ?1`).bind(target.email).first<AccountRow>();
    if (!row) {
      await db.prepare("INSERT INTO accounts (email, status, decided_at, decided_by) VALUES (?1, ?2, ?3, ?4)").bind(target.email, status, now, by).run();
      row = await db.prepare(`SELECT ${COLS} FROM accounts WHERE email = ?1`).bind(target.email).first<AccountRow>();
    }
  }
  if (!row) throw new HttpError(404, "no_account", "No such account");
  await db.prepare("UPDATE accounts SET status = ?2, decided_at = ?3, decided_by = ?4 WHERE id = ?1").bind(row.id, status, status === "pending" ? 0 : now, status === "pending" ? "" : by).run();
  // This isolate forgets its approval right away; other isolates within a minute.
  if (row.uid) approvedCache.delete(row.uid);
  return { ...row, status };
}
