// Test doubles: D1 on node:sqlite (real SQL, real migrations), KV with TTLs, Workers AI, ctx.

import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { Env } from "../src/env";

class FakeStmt {
  constructor(private db: DatabaseSync, readonly sql: string, private args: unknown[] = []) {}
  bind(...args: unknown[]) {
    return new FakeStmt(this.db, this.sql, args);
  }
  private returnsRows() {
    return /^\s*(SELECT|WITH)\b/i.test(this.sql) || /\bRETURNING\b/i.test(this.sql);
  }
  async first<T>(col?: string): Promise<T | null> {
    const r = this.db.prepare(this.sql).get(...(this.args as any[])) as any;
    if (!r) return null;
    return (col ? r[col] : { ...r }) as T;
  }
  async all<T>() {
    const rows = this.db.prepare(this.sql).all(...(this.args as any[])).map(r => ({ ...(r as object) }));
    return { results: rows as T[], success: true, meta: {} };
  }
  async run() {
    if (this.returnsRows()) return this.all();
    const r = this.db.prepare(this.sql).run(...(this.args as any[]));
    return { results: [], success: true, meta: { changes: Number(r.changes) } };
  }
}

export class FakeD1 {
  sqlite = new DatabaseSync(":memory:");
  /** [beforeMigration] runs before each migration file (to put old data in place, e.g. for grandfathering). */
  constructor(beforeMigration?: (file: string, sqlite: DatabaseSync) => void) {
    const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "migrations");
    for (const f of readdirSync(dir).filter(f => f.endsWith(".sql")).sort()) {
      beforeMigration?.(f, this.sqlite);
      this.sqlite.exec(readFileSync(join(dir, f), "utf8"));
    }
  }
  prepare(sql: string) {
    return new FakeStmt(this.sqlite, sql);
  }
  async batch(stmts: FakeStmt[]) {
    this.sqlite.exec("BEGIN");
    try {
      const out = [];
      for (const s of stmts) out.push(await s.run());
      this.sqlite.exec("COMMIT");
      return out;
    } catch (e) {
      this.sqlite.exec("ROLLBACK");
      throw e;
    }
  }
  rows(sql: string): any[] {
    return this.sqlite.prepare(sql).all().map(r => ({ ...(r as object) }));
  }
  /** Every text value in every table, for "no message content stored" checks. */
  dump(): string {
    const tables = this.rows("SELECT name FROM sqlite_master WHERE type = 'table'").map(r => r.name as string);
    return tables.map(t => JSON.stringify(this.rows(`SELECT * FROM "${t}"`))).join("\n");
  }
}

export class FakeKV {
  m = new Map<string, { v: string; exp: number }>();
  async get(k: string, type?: string): Promise<any> {
    const e = this.m.get(k);
    if (!e || (e.exp && e.exp < Date.now())) return null;
    return type === "json" ? JSON.parse(e.v) : e.v;
  }
  async put(k: string, v: string, o?: { expirationTtl?: number }) {
    this.m.set(k, { v, exp: o?.expirationTtl ? Date.now() + o.expirationTtl * 1000 : 0 });
  }
  async delete(k: string) {
    this.m.delete(k);
  }
}

export class FakeAI {
  calls: { model: string; inputs: Record<string, unknown> }[] = [];
  fail: string | null = null;
  reply = "Hello from Workers AI";
  async run(model: string, inputs: Record<string, unknown>) {
    this.calls.push({ model, inputs });
    if (this.fail) throw new Error(this.fail);
    if (inputs.stream) {
      const enc = new TextEncoder();
      const parts = this.reply.split(" ");
      return new ReadableStream<Uint8Array>({
        start(c) {
          parts.forEach((p, i) => c.enqueue(enc.encode(`data: ${JSON.stringify({ response: (i ? " " : "") + p })}\n\n`)));
          c.enqueue(enc.encode(`data: ${JSON.stringify({ response: "", usage: { prompt_tokens: 5, completion_tokens: 4, total_tokens: 9 } })}\n\n`));
          c.enqueue(enc.encode("data: [DONE]\n\n"));
          c.close();
        },
      });
    }
    return { response: this.reply, usage: { prompt_tokens: 5, completion_tokens: 4, total_tokens: 9 } };
  }
}

export class FakeCtx {
  promises: Promise<unknown>[] = [];
  waitUntil(p: Promise<unknown>) {
    this.promises.push(p);
  }
  passThroughOnException() {}
  async settle() {
    while (this.promises.length) await Promise.allSettled(this.promises.splice(0));
  }
}

export const SECRET = "test-secret-0123456789abcdefghijklmnopqrstuvwxyz";

/** base64url(SHA-256(s)), like blaze-auth. */
export async function sessHash(token: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
  let s = "";
  for (const x of d) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Puts a blaze-auth session for [email] into [kv]; returns its token. */
export async function makeSession(kv: FakeKV, id: string, email: string): Promise<string> {
  const token = "bz_" + id.padEnd(43, "x").replace(/[^A-Za-z0-9_-]/g, "x");
  const h = await sessHash(token);
  const now = Math.floor(Date.now() / 1000);
  await kv.put(`sess:${h}`, JSON.stringify({ uid: id, created: now, exp: now + 3600, client: "test" }));
  const prev = await kv.get(`user:${id}`, "json");
  await kv.put(`user:${id}`, JSON.stringify({ id, email, name: "T", sessions: [...(prev?.sessions || []), h] }));
  return token;
}

export function makeEnv(over: Partial<Env> = {}): { env: Env; db: FakeD1; kv: FakeKV; ai: FakeAI } {
  const db = new FakeD1();
  const kv = new FakeKV();
  const ai = new FakeAI();
  const env = {
    DB: db as unknown as D1Database,
    AI: ai,
    AUTH_KV: kv as unknown as KVNamespace,
    KEY_ENCRYPTION_SECRET: SECRET,
    ADMIN_EMAILS: "luke@example.com",
    ADMIN_TOKEN: "admin-token-0123456789abcdef",
    ...over,
  } as Env;
  return { env, db, kv, ai };
}

export function sse(chunks: string[]): Response {
  const enc = new TextEncoder();
  return new Response(new ReadableStream({
    start(c) {
      for (const x of chunks) c.enqueue(enc.encode(`data: ${x}\n\n`));
      c.enqueue(enc.encode("data: [DONE]\n\n"));
      c.close();
    },
  }), { status: 200, headers: { "Content-Type": "text/event-stream" } });
}
