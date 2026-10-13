// Per-account daily quotas by plan. One D1 upsert per request (atomic, RETURNING the new count).

import { DEFAULT_PLAN, type PlanLimits } from "./defaults";
import { msToUtcMidnight, utcDay } from "./util";

export interface QuotaVerdict {
  ok: boolean;
  reason?: "requests" | "tokens";
  remaining: number;     // requests left today after this one
  resetMs: number;       // until the next UTC midnight
}

/** Pure: decide from the counts after this request was added. */
export function judge(plan: PlanLimits, requests: number, tokens: number, now = Date.now()): QuotaVerdict {
  const resetMs = msToUtcMidnight(now);
  const remaining = Math.max(0, plan.daily_requests - requests);
  if (requests > plan.daily_requests) return { ok: false, reason: "requests", remaining: 0, resetMs };
  if (plan.daily_tokens > 0 && tokens >= plan.daily_tokens) return { ok: false, reason: "tokens", remaining, resetMs };
  return { ok: true, remaining, resetMs };
}

/** Pure: which plan an account is on. Unknown/removed plans fall back to the default. */
export function planFor(email: string, assignments: Map<string, string>, plans: PlanLimits[]): PlanLimits {
  const want = assignments.get(email.trim().toLowerCase()) ?? DEFAULT_PLAN;
  return plans.find(p => p.id === want) ?? plans.find(p => p.id === DEFAULT_PLAN) ?? plans[0] ?? { id: DEFAULT_PLAN, price_usd: 0, daily_requests: 0, daily_tokens: 0 };
}

export async function chargeRequest(db: D1Database, uid: string, now = Date.now()): Promise<{ requests: number; tokens: number }> {
  const row = await db.prepare(
    "INSERT INTO usage (uid, day, requests, tokens) VALUES (?1, ?2, 1, 0) " +
    "ON CONFLICT (uid, day) DO UPDATE SET requests = requests + 1 RETURNING requests, tokens",
  ).bind(uid, utcDay(now)).first<{ requests: number; tokens: number }>();
  return row ?? { requests: 1, tokens: 0 };
}

/** Give the request back when no model could answer it. */
export async function refundRequest(db: D1Database, uid: string, now = Date.now()): Promise<void> {
  await db.prepare("UPDATE usage SET requests = MAX(0, requests - 1) WHERE uid = ?1 AND day = ?2").bind(uid, utcDay(now)).run();
}

export async function addTokens(db: D1Database, uid: string, tokens: number, now = Date.now()): Promise<void> {
  if (!(tokens > 0)) return;
  await db.prepare("UPDATE usage SET tokens = tokens + ?3 WHERE uid = ?1 AND day = ?2").bind(uid, utcDay(now), Math.round(tokens)).run();
}
