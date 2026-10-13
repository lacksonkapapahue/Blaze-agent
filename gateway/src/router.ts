// Task routing and failover rules. Pure functions, no I/O, so they're unit-tested directly.
// Mirrors Blaze Android v0.23.4 Auto (agent/ModelMemory.kt + RoutePlan): models that are cooling
// are never tried, a hard failure that has cooled down goes to the back until it works again, and
// a rejected key or provider-wide cap rests the whole provider ("<provider>|*").

import type { Candidate } from "./defaults";

export const TASKS = ["chat", "quick", "code", "vision", "long", "agent"] as const;
export type Task = (typeof TASKS)[number];

const ALIASES: Record<string, Task> = {
  chat: "chat", quick: "quick", fast: "quick", code: "code", coding: "code", vision: "vision", image: "vision",
  images: "vision", long: "long", agent: "agent", tools: "agent", "agent/tools": "agent", tool: "agent",
};

export function normalizeTask(v: unknown): Task | null {
  if (typeof v !== "string") return null;
  return ALIASES[v.trim().toLowerCase()] ?? null;
}

/** "blaze-code" → code. "blaze-auto", "auto", anything else → null (infer). */
export function taskFromModel(model: unknown): Task | null {
  if (typeof model !== "string") return null;
  const m = /^blaze[-/](\w+)$/i.exec(model.trim());
  return m ? normalizeTask(m[1]) : null;
}

export const LONG_CHARS = 48_000; // ~12k tokens

interface Msg { role?: unknown; content?: unknown }

function partsOf(content: unknown): unknown[] {
  return Array.isArray(content) ? content : [];
}

export function hasImages(messages: Msg[]): boolean {
  return messages.some(m => partsOf(m.content).some(p => {
    const t = (p as { type?: unknown })?.type;
    return t === "image_url" || t === "input_image" || t === "image";
  }));
}

/** Characters of text in the conversation (images not counted). */
export function textChars(messages: Msg[]): number {
  let n = 0;
  for (const m of messages) {
    if (typeof m.content === "string") n += m.content.length;
    else for (const p of partsOf(m.content)) {
      const t = (p as { text?: unknown })?.text;
      if (typeof t === "string") n += t.length;
    }
  }
  return n;
}

const CODE_CUES = /```|\b(function|def|class|import|const|let|var|return|public static|#include|SELECT|stack ?trace|traceback|exception|compile|regex|typescript|javascript|python|kotlin|golang|rust|java|sql|bash|dockerfile|refactor|debug)\b/i;

function lastUserText(messages: Msg[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]!;
    if (m.role !== "user") continue;
    if (typeof m.content === "string") return m.content;
    return partsOf(m.content).map(p => (p as { text?: unknown })?.text).filter(t => typeof t === "string").join("\n");
  }
  return "";
}

/** No tag: images → vision, tools → agent, long input → long, code cues → code, else chat. */
export function inferTask(body: { messages?: unknown; tools?: unknown; functions?: unknown }): Task {
  const messages = Array.isArray(body.messages) ? (body.messages as Msg[]) : [];
  if (hasImages(messages)) return "vision";
  const tools = (Array.isArray(body.tools) && body.tools.length > 0) || (Array.isArray(body.functions) && body.functions.length > 0);
  if (tools) return "agent";
  if (textChars(messages) > LONG_CHARS) return "long";
  if (CODE_CUES.test(lastUserText(messages).slice(0, 4000))) return "code";
  return "chat";
}

export function pickTask(body: { blaze_task?: unknown; model?: unknown; messages?: unknown; tools?: unknown }): { task: Task; source: "tag" | "model" | "inferred" } {
  const t = normalizeTask(body.blaze_task);
  if (t) return { task: t, source: "tag" };
  const m = taskFromModel(body.model);
  if (m) return { task: m, source: "model" };
  return { task: inferTask(body), source: "inferred" };
}

// ---------------- model memory ----------------

export type MarkState = "ok" | "limited" | "failed";

export interface Mark {
  state: MarkState;
  until: number;   // cooling until (ms epoch); 0 = not cooling
  reason: string;
  okAt: number;
  failAt: number;
}

export const DEMOTE_MS = 6 * 3600_000;
export const markKey = (provider: string, model: string) => `${provider}|${model}`;
export const providerKey = (provider: string) => `${provider}|*`;

export function coolingUntil(marks: Map<string, Mark>, c: Candidate): number {
  return Math.max(marks.get(markKey(c.provider, c.model))?.until ?? 0, marks.get(providerKey(c.provider))?.until ?? 0);
}

export function isDemoted(marks: Map<string, Mark>, c: Candidate, now: number): boolean {
  const m = marks.get(markKey(c.provider, c.model));
  return !!m && m.state === "failed" && m.okAt < m.failAt && now - m.failAt < DEMOTE_MS;
}

export interface Plan {
  ready: Candidate[];
  cooling: { c: Candidate; until: number }[];
  skipped: Candidate[]; // provider disabled / no usable key / no AI binding
}

/**
 * The order to try models in for one request: the task's list in admin order, minus providers that
 * can't be used and models cooling down; cooled-down hard failures move to the end.
 */
export function planCandidates(order: Candidate[], usable: (provider: string) => boolean, marks: Map<string, Mark>, now: number): Plan {
  const fresh: Candidate[] = [];
  const demoted: Candidate[] = [];
  const cooling: { c: Candidate; until: number }[] = [];
  const skipped: Candidate[] = [];
  const seen = new Set<string>();
  for (const c of order) {
    const k = markKey(c.provider, c.model);
    if (seen.has(k)) continue;
    seen.add(k);
    if (!usable(c.provider)) { skipped.push(c); continue; }
    const until = coolingUntil(marks, c);
    if (until > now) { cooling.push({ c, until }); continue; }
    (isDemoted(marks, c, now) ? demoted : fresh).push(c);
  }
  cooling.sort((a, b) => a.until - b.until);
  return { ready: [...fresh, ...demoted], cooling, skipped };
}

// ---------------- failure classification ----------------

export type Kind = "rate_limit" | "provider_limit" | "key" | "gone" | "too_large" | "bad_request" | "server" | "timeout";
/** What to cool: this model, the whole provider, only the key that was used, or nothing. */
export type Scope = "model" | "provider" | "key" | "none";

export interface Outcome { kind: Kind; coolMs: number; scope: Scope }

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** What a failed upstream call means. status 0 = timeout / network error / stream cut. */
export function classify(provider: string, status: number, text: string, retryAfterMs?: number | null): Outcome {
  const t = (text || "").toLowerCase();
  const has = (...w: string[]) => w.some(x => t.includes(x));
  if (status === 413 || has("too large", "context length", "context_length", "maximum context", "too many tokens", "reduce the length")) {
    return { kind: "too_large", coolMs: 0, scope: "none" };
  }
  if (status === 429 || status === 402 || has("rate limit", "rate_limit", "quota", "resource_exhausted", "too many requests", "capacity temporarily exceeded", "daily free allocation")) {
    const providerWide = status === 402 || has("credits", "insufficient balance", "payment required", "daily free allocation", "neurons") ||
      (provider === "openrouter" && has("free-models-per-day", "per-day"));
    return { kind: providerWide ? "provider_limit" : "rate_limit", coolMs: clamp(retryAfterMs ?? 60_000, 3_000, 6 * 3600_000), scope: providerWide ? "provider" : "model" };
  }
  if (status === 401 || has("invalid api key", "incorrect api key", "api key not valid", "unauthorized", "authentication")) {
    return { kind: "key", coolMs: 30 * 60_000, scope: "key" };
  }
  if (status === 404 || has("decommissioned", "deprecated", "no longer available", "no longer supported", "model_not_found", "does not exist", "unknown model", "invalid model", "no endpoints found")) {
    return { kind: "gone", coolMs: 24 * 3600_000, scope: "model" };
  }
  if (status === 403) return { kind: "gone", coolMs: 6 * 3600_000, scope: "model" };
  // A 400 is usually the request itself (bad schema, unsupported parameter). Never cool a shared model
  // because of one client's request: try the next model, and the caller gives up after a couple.
  if (status >= 400 && status < 500) return { kind: "bad_request", coolMs: 0, scope: "none" };
  if (status >= 500) return { kind: "server", coolMs: 2 * 60_000, scope: "model" };
  return { kind: "timeout", coolMs: 3 * 60_000, scope: "model" };
}

/** Retry-After (seconds or HTTP date) and the x-ratelimit-reset-* headers Groq/OpenAI send. */
export function retryAfterMs(h: Headers, now = Date.now()): number | null {
  const ra = h.get("retry-after");
  if (ra) {
    const s = Number(ra);
    if (Number.isFinite(s)) return s * 1000;
    const d = Date.parse(ra);
    if (Number.isFinite(d)) return Math.max(0, d - now);
  }
  for (const name of ["x-ratelimit-reset-requests", "x-ratelimit-reset-tokens", "x-ratelimit-reset"]) {
    const v = h.get(name);
    if (!v) continue;
    const ms = durationMs(v);
    if (ms !== null) return ms;
  }
  return null;
}

/** "1m30s", "2.5s", "250ms", "12" (seconds) → ms. */
export function durationMs(v: string): number | null {
  const s = v.trim();
  if (/^\d+(\.\d+)?$/.test(s)) return Number(s) * 1000;
  const re = /(\d+(?:\.\d+)?)(ms|h|m|s)/g;
  let total = 0, any = false;
  for (const m of s.matchAll(re)) {
    any = true;
    const n = Number(m[1]);
    total += m[2] === "ms" ? n : m[2] === "s" ? n * 1000 : m[2] === "m" ? n * 60_000 : n * 3600_000;
  }
  return any ? total : null;
}

/** Apply an outcome to the in-memory marks (the caller persists the changed entry). */
export function applyOutcome(marks: Map<string, Mark>, c: Candidate, o: Outcome, now: number): { key: string; mark: Mark } | null {
  if (o.scope !== "model" && o.scope !== "provider") return null;
  const key = o.scope === "provider" ? providerKey(c.provider) : markKey(c.provider, c.model);
  const prev = marks.get(key) ?? { state: "ok", until: 0, reason: "", okAt: 0, failAt: 0 };
  const hard = o.kind === "gone" || o.kind === "server" || o.kind === "timeout";
  const mark: Mark = { ...prev, state: hard ? "failed" : "limited", until: now + o.coolMs, reason: o.kind, failAt: now };
  marks.set(key, mark);
  return { key, mark };
}

export function markOk(marks: Map<string, Mark>, c: Candidate, now: number): { key: string; mark: Mark } {
  const key = markKey(c.provider, c.model);
  const prev = marks.get(key) ?? { state: "ok", until: 0, reason: "", okAt: 0, failAt: 0 };
  const mark: Mark = { ...prev, state: "ok", until: 0, reason: "", okAt: now };
  marks.set(key, mark);
  return { key, mark };
}
