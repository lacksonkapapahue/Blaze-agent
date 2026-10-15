// Small helpers: encoding, hashing, JSON responses.

const enc = new TextEncoder();

export class HttpError extends Error {
  constructor(public status: number, public code: string, message?: string, public headers: Record<string, string> = {}) {
    super(message || code);
  }
}

export function b64(bytes: Uint8Array): string {
  let s = "";
  for (const x of bytes) s += String.fromCharCode(x);
  return btoa(s);
}

export function fromB64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function b64url(bytes: ArrayBuffer | Uint8Array): string {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  return b64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Same encoding blaze-auth uses for session hashes: base64url(SHA-256(s)). */
export async function sha256(s: string): Promise<string> {
  return b64url(await crypto.subtle.digest("SHA-256", enc.encode(s)));
}

/** Constant-time compare. */
export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

/**
 * CORS headers for /v1. No Access-Control-Allow-Origin here: Blaze's apps are native and need no CORS, and the
 * admin page is same-origin, so by default no other website may read the gateway's answers. [withCors] adds
 * Allow-Origin only for an Origin listed in the optional ALLOWED_ORIGINS env var, and strips these headers otherwise.
 */
export const CORS: Record<string, string> = {
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Expose-Headers": "X-Blaze-Task, X-Blaze-Provider, X-Blaze-Model, X-RateLimit-Remaining-Requests, Retry-After",
};

export function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...headers },
  });
}

/** OpenAI-style error body, so OpenAI SDKs show the message. */
export function apiError(status: number, code: string, message: string, headers: Record<string, string> = {}): Response {
  return json({ error: { message, type: code, code } }, status, { ...CORS, ...headers });
}

export async function readJSON<T = Record<string, unknown>>(req: Request, maxBytes = 8 * 1024 * 1024): Promise<T> {
  const len = Number(req.headers.get("Content-Length") || "0");
  if (len > maxBytes) throw new HttpError(413, "too_large", "Request body is too large");
  const text = await req.text();
  if (text.length > maxBytes) throw new HttpError(413, "too_large", "Request body is too large");
  try {
    const v = JSON.parse(text);
    if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error("not an object");
    return v as T;
  } catch {
    throw new HttpError(400, "bad_json", "Body must be a JSON object");
  }
}

export function utcDay(ms = Date.now()): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/** Milliseconds until the next UTC midnight. */
export function msToUtcMidnight(ms = Date.now()): number {
  const d = new Date(ms);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) - ms;
}

/** ALLOWED_ORIGINS ("https://a.example, https://b.example") as exact origins; "*" is ignored on purpose. */
export function allowedOrigins(env: { ALLOWED_ORIGINS?: string }): Set<string> {
  return new Set((env.ALLOWED_ORIGINS || "").split(/[\s,]+/).map(o => o.trim().replace(/\/+$/, "").toLowerCase()).filter(o => /^https?:\/\/[^/\s*]+$/.test(o)));
}

/**
 * Applies the CORS policy to a response: a cross-origin caller listed in ALLOWED_ORIGINS gets its own origin back
 * (with Vary: Origin); anyone else gets no Access-Control-* headers at all, so browsers block the read.
 */
export function withCors(req: Request, env: { ALLOWED_ORIGINS?: string }, res: Response): Response {
  const origin = (req.headers.get("Origin") || "").replace(/\/+$/, "").toLowerCase();
  const allowed = origin !== "" && allowedOrigins(env).has(origin);
  const out = new Response(res.body, res);
  for (const k of [...out.headers.keys()]) if (k.toLowerCase().startsWith("access-control-")) out.headers.delete(k);
  if (allowed) {
    for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
    out.headers.set("Access-Control-Allow-Origin", req.headers.get("Origin")!.replace(/\/+$/, ""));
    out.headers.append("Vary", "Origin");
    if (req.method === "OPTIONS") out.headers.set("Access-Control-Max-Age", "86400");
  }
  return out;
}
