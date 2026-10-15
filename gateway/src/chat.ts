// POST /v1/chat/completions: Blaze account → waiting list → quota → task → ordered models → failover.

import { requireAccess } from "./access";
import { requireUser } from "./auth";
import { isRoutable } from "./catalog";
import { decryptKey } from "./crypto";
import { loadConfig, loadMarks, saveMarkStmt, statStmt, keyResultStmt, type Config, type KeyRow } from "./db";
import type { Candidate } from "./defaults";
import type { Env } from "./env";
import { callOpenAI, callWorkersAI, upstreamBody, usageTap, type Upstream } from "./providers";
import { addTokens, chargeRequest, judge, planFor, refundRequest } from "./quota";
import { applyOutcome, classify, coolingUntil, markOk, pickTask, planCandidates, retryAfterMs, type Mark, type Task } from "./router";
import { CORS, HttpError, apiError, readJSON } from "./util";

export const MAX_ATTEMPTS = 6;
export const HEADER_TIMEOUT_MS = 30_000;
export const TOTAL_BUDGET_MS = 100_000;

const rr = new Map<string, number>(); // round-robin position per provider
const plainKeys = new Map<string, string>(); // `${id}:${enc}` → decrypted, per isolate

export function resetRotationForTests(): void {
  rr.clear();
  plainKeys.clear();
}

export function usableKeys(cfg: Config, provider: string, now: number): KeyRow[] {
  return cfg.keys.filter(k => k.provider_id === provider && k.enabled && k.cooling_until <= now);
}

/** Next key for [provider] in rotation, skipping keys already tried in this request. */
export function nextKey(cfg: Config, provider: string, tried: Set<number>, now: number): KeyRow | null {
  const ks = usableKeys(cfg, provider, now).filter(k => !tried.has(k.id));
  if (!ks.length) return null;
  const i = (rr.get(provider) ?? 0) % ks.length;
  rr.set(provider, i + 1);
  return ks[i]!;
}

export function isUsable(cfg: Config, env: Env, provider: string, now: number): boolean {
  const p = cfg.providers.get(provider);
  if (!p || !p.enabled) return false;
  if (p.type === "workers-ai") return !!env.AI;
  return usableKeys(cfg, provider, now).length > 0;
}

async function plainKey(env: Env, k: KeyRow): Promise<string> {
  const id = `${k.id}:${k.enc}`;
  let v = plainKeys.get(id);
  if (!v) {
    v = await decryptKey(env.KEY_ENCRYPTION_SECRET, k.provider_id, k.enc);
    if (plainKeys.size > 500) plainKeys.clear();
    plainKeys.set(id, v);
  }
  return v;
}

export interface Attempt { provider: string; model: string; status: number; kind: string }

export interface RouteResult {
  upstream?: Upstream;
  candidate?: Candidate;
  attempts: Attempt[];
  /** When every model was busy: the soonest one frees up (ms epoch). */
  retryAt?: number;
  clientError?: { status: number; message: string };
}

/** blaze_avoid from the app: at most 8 "provider/model" strings. */
export function avoidSet(v: unknown): Set<string> {
  return new Set(Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && x.length > 0 && x.length < 200).slice(0, 8) : []);
}

/**
 * Tries the task's models in order until one answers. Exported for tests: [deps] lets them swap the
 * network and clock. Persists health through [persist] (the caller hands it to waitUntil).
 */
export async function route(
  env: Env, cfg: Config, marks: Map<string, Mark>, task: Task, body: Record<string, unknown>,
  persist: (stmts: D1PreparedStatement[]) => void,
  deps: { fetch?: typeof fetch; now?: () => number } = {},
): Promise<RouteResult> {
  const now = deps.now ?? Date.now;
  const start = now();
  // App v0.26.2: routes ("provider/model") that just came back empty (reasoning only) or broke mid-stream for this
  // request; they go last so the next model in the lineup answers. Never empties the lineup.
  const avoid = avoidSet(body.blaze_avoid);
  const lineup = (cfg.routes[task] ?? []).filter(c => isRoutable(c, cfg.paid ?? new Set()));
  const preferred = lineup.filter(c => !avoid.has(`${c.provider}/${c.model}`));
  const plan = planCandidates(preferred.length ? [...preferred, ...lineup.filter(c => avoid.has(`${c.provider}/${c.model}`))] : lineup, p => isUsable(cfg, env, p, start), marks, start);
  const attempts: Attempt[] = [];
  let badRequests = 0;
  let lastBad: { status: number; message: string } | undefined;

  outer: for (const c of plan.ready) {
    const provider = cfg.providers.get(c.provider)!;
    const tried = new Set<number>();
    for (;;) {
      if (attempts.length >= MAX_ATTEMPTS || now() - start > TOTAL_BUDGET_MS) break outer;
      if (coolingUntil(marks, c) > now()) break; // a provider-wide mark from an earlier attempt
      let up: Upstream;
      let key: KeyRow | null = null;
      const t0 = now();
      const budget = Math.min(HEADER_TIMEOUT_MS, Math.max(5_000, TOTAL_BUDGET_MS - (t0 - start)));
      if (provider.type === "workers-ai") {
        up = await callWorkersAI(env.AI, c.model, body, budget);
      } else {
        key = nextKey(cfg, c.provider, tried, t0);
        if (!key) break;
        tried.add(key.id);
        let secret: string;
        try {
          secret = await plainKey(env, key);
        } catch {
          key.enabled = false; // undecryptable (secret changed): skip it in this isolate
          persist([keyResultStmt(env.DB, key.id, false, t0, 0, "can't decrypt: KEY_ENCRYPTION_SECRET changed?")]);
          continue;
        }
        up = await callOpenAI(c.provider, provider.base_url, secret, upstreamBody(body, c.model), budget, deps.fetch);
      }
      const t1 = now();
      if (up.status >= 200 && up.status < 300 && up.body) {
        const ok = markOk(marks, c, t1);
        const stmts = [saveMarkStmt(env.DB, ok.key, ok.mark), statStmt(env.DB, c, true, t1 - t0, t1)];
        if (key) { key.cooling_until = 0; stmts.push(keyResultStmt(env.DB, key.id, true, t1)); }
        persist(stmts);
        attempts.push({ provider: c.provider, model: c.model, status: up.status, kind: "ok" });
        return { upstream: up, candidate: c, attempts };
      }
      const o = classify(c.provider, up.status, up.errorText, retryAfterMs(up.headers, t1));
      attempts.push({ provider: c.provider, model: c.model, status: up.status, kind: o.kind });
      const stmts = [statStmt(env.DB, c, false, t1 - t0, t1)];
      if (o.scope === "key" && key) {
        key.cooling_until = t1 + o.coolMs;
        stmts.push(keyResultStmt(env.DB, key.id, false, t1, key.cooling_until, `${up.status} ${o.kind}`));
        persist(stmts);
        continue; // same model, next key
      }
      if (key && (o.kind === "rate_limit" || o.kind === "server" || o.kind === "timeout")) {
        stmts.push(keyResultStmt(env.DB, key.id, false, t1, 0, `${up.status} ${o.kind}`));
        // Like Android's KeyRing: on a 429 another key may still have room for this model.
        if (o.kind === "rate_limit" && usableKeys(cfg, c.provider, t1).some(k => !tried.has(k.id))) {
          persist(stmts);
          continue;
        }
      }
      const changed = applyOutcome(marks, c, o, t1);
      if (changed) stmts.push(saveMarkStmt(env.DB, changed.key, changed.mark));
      persist(stmts);
      if (o.kind === "bad_request") {
        badRequests++;
        lastBad = { status: up.status, message: up.errorText.slice(0, 300) };
        if (badRequests >= 2) return { attempts, clientError: lastBad };
      }
      break; // next candidate
    }
  }
  const coolTimes = [...plan.cooling.map(x => x.until), ...[...marks.values()].map(m => m.until)].filter(t => t > now());
  return { attempts, retryAt: coolTimes.length ? Math.min(...coolTimes) : undefined, clientError: badRequests > 0 && attempts.every(a => a.kind === "bad_request") ? lastBad : undefined };
}

export async function chatCompletions(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const user = await requireUser(env, req);
  await requireAccess(env, user);
  const body = await readJSON(req);
  if (!Array.isArray(body.messages) || body.messages.length === 0) throw new HttpError(400, "invalid_request_error", "'messages' must be a non-empty array");
  if (!env.KEY_ENCRYPTION_SECRET) throw new HttpError(503, "not_configured", "The gateway isn't set up yet (KEY_ENCRYPTION_SECRET missing)");

  const cfg = await loadConfig(env.DB);
  const plan = planFor(user.email, cfg.assignments, cfg.plans);
  const used = await chargeRequest(env.DB, user.id);
  const verdict = judge(plan, used.requests, used.tokens);
  const rlHeaders = { "X-RateLimit-Remaining-Requests": String(verdict.remaining) };
  if (!verdict.ok) {
    const s = Math.ceil(verdict.resetMs / 1000);
    return apiError(429, "quota_exceeded", `You've used today's ${verdict.reason === "tokens" ? "token" : "message"} allowance on the ${plan.id} plan. It resets at 00:00 UTC.`, { ...rlHeaders, "Retry-After": String(s) });
  }

  const { task, source } = pickTask(body);
  const marks = await loadMarks(env.DB);
  const persist = (stmts: D1PreparedStatement[]) => { if (stmts.length) ctx.waitUntil(env.DB.batch(stmts).then(() => undefined, () => undefined)); };
  const r = await route(env, cfg, marks, task, body, persist);

  if (!r.upstream || !r.candidate) {
    ctx.waitUntil(refundRequest(env.DB, user.id).catch(() => undefined));
    if (r.clientError) return apiError(r.clientError.status || 400, "invalid_request_error", `The model rejected the request: ${r.clientError.message || "bad request"}`, rlHeaders);
    const wait = r.retryAt ? Math.max(1, Math.ceil((r.retryAt - Date.now()) / 1000)) : 30;
    return apiError(503, "blaze_models_busy", `Every model for '${task}' is busy right now. The next one frees up in about ${wait}s.`, { ...rlHeaders, "Retry-After": String(wait) });
  }

  const headers = new Headers({
    ...CORS,
    ...rlHeaders,
    "Content-Type": r.upstream.headers.get("Content-Type") || (body.stream ? "text/event-stream" : "application/json"),
    "Cache-Control": "no-store",
    "X-Blaze-Task": `${task}${source === "inferred" ? ";inferred" : ""}`,
    "X-Blaze-Provider": r.candidate.provider,
    "X-Blaze-Model": r.candidate.model,
  });
  const out = usageTap(r.upstream.body!, n => ctx.waitUntil(addTokens(env.DB, user.id, n).catch(() => undefined)));
  return new Response(out, { status: 200, headers });
}

