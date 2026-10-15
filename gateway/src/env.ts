// What the Worker is bound to. Everything here is configured in wrangler.toml or as a secret.

/** The bit of the Workers AI binding the gateway uses. */
export interface AiBinding {
  run(model: string, inputs: Record<string, unknown>, options?: Record<string, unknown>): Promise<unknown>;
  /** Cloudflare's model catalogue (used by model discovery; optional so test doubles can omit it). */
  models?(params?: Record<string, unknown>): Promise<unknown[]>;
}

export interface Env {
  /** D1: providers, encrypted keys, task routes, plans, usage, model health. */
  DB: D1Database;
  /** Workers AI: the last-resort fallback, no key needed. */
  AI?: AiBinding;
  /** Service binding to the blaze-auth Worker (sign-in for the admin page, /v1/me fallback). */
  AUTH?: Fetcher;
  /** blaze-auth's KV namespace, bound read-only by convention: lets the gateway check a session
   *  token with two KV reads instead of a call to blaze-auth. */
  AUTH_KV?: KVNamespace;
  /** Used only when neither AUTH nor AUTH_KV is bound (local dev against a running blaze-auth). */
  BLAZE_AUTH_URL?: string;

  /** Secret. AES-GCM key material for provider keys at rest (any long random string). */
  KEY_ENCRYPTION_SECRET?: string;
  /** Secret or var. Comma-separated Blaze account emails allowed into /admin. */
  ADMIN_EMAILS?: string;
  /** Secret. Fallback admin login when blaze-auth isn't reachable. */
  ADMIN_TOKEN?: string;
  /** Var, optional. Comma-separated web origins allowed to call /v1 from a browser (CORS). Empty = none:
   *  the native apps don't need CORS and the admin page is same-origin. */
  ALLOWED_ORIGINS?: string;
}

export const VERSION = "0.3.0";
