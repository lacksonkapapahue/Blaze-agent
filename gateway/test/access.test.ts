// 0.3.0: the waiting list and the plan-assignment fix, end to end through the Worker's fetch().

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import worker from "../src/index";
import { resetDbCacheForTests } from "../src/db";
import { clearAuthCache } from "../src/auth";
import { resetRotationForTests } from "../src/chat";
import { clearAccessCache, WAITLISTED_MESSAGE } from "../src/access";
import { FakeCtx, FakeD1, makeEnv, makeSession, type FakeKV } from "./helpers";
import type { Env } from "../src/env";

const BASE = "https://blaze-gateway.blaze-app.workers.dev";
const ADMIN = "admin-token-0123456789abcdef";
let env: Env, db: FakeD1, kv: FakeKV, ctx: FakeCtx;

async function call(method: string, path: string, body?: unknown, token?: string) {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await worker.fetch(new Request(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), env, ctx as unknown as ExecutionContext);
  await ctx.settle();
  return res;
}
async function admin(method: string, path: string, body?: unknown) {
  const r = await call(method, "/admin/api/" + path, body, ADMIN);
  return { status: r.status, body: await r.json<any>() };
}
const chat = (token: string) => call("POST", "/v1/chat/completions", { model: "blaze-auto", messages: [{ role: "user", content: "hello" }] }, token);
async function errCode(r: Response) { return (await r.json<any>()).error.code; }
async function accounts() { return (await admin("GET", "state")).body.accounts as any[]; }
/** A fresh isolate: other isolates' caches don't know about this one's admin changes. */
function newIsolate() { resetDbCacheForTests(); clearAccessCache(); clearAuthCache(); }

beforeEach(() => {
  newIsolate();
  resetRotationForTests();
  ({ env, db, kv } = makeEnv());
  ctx = new FakeCtx();
  // Every chat goes to Workers AI (the fake binding answers): no network needed.
  vi.stubGlobal("fetch", async () => new Response("no network in tests", { status: 500 }));
});
afterEach(() => vi.unstubAllGlobals());

describe("waiting list", () => {
  it("a new account is recorded as pending and refused with 403 waitlisted", async () => {
    const t = await makeSession(kv, "u_new1", "New.Person@Example.com");
    expect((await call("GET", "/v1/models", undefined, t)).status).toBe(200); // signing in only records
    const r = await chat(t);
    expect(r.status).toBe(403);
    const body = await r.json<any>();
    expect(body.error.code).toBe("waitlisted");
    expect(body.error.message).toBe(WAITLISTED_MESSAGE);
    const a = (await accounts()).find(x => x.email === "new.person@example.com");
    expect(a).toMatchObject({ status: "pending", uid: "u_new1", name: "T", requests: 2 });
    expect(a.first_seen).toBeGreaterThan(0);
    expect(a.last_seen).toBeGreaterThanOrEqual(a.first_seen);
    expect(db.rows("SELECT * FROM usage")).toEqual([]); // a waitlisted call costs no quota
  });

  it("approve → allowed (on the chosen plan); reject → access_rejected; undo puts them back on the list", async () => {
    const t = await makeSession(kv, "u_new2", "wait@example.com");
    expect(await errCode(await chat(t))).toBe("waitlisted");
    const id = (await accounts()).find(x => x.email === "wait@example.com").id;

    expect((await admin("POST", "access/approve", { id, plan: "standard" })).status).toBe(200);
    newIsolate();
    expect((await chat(t)).status).toBe(200);
    expect((await admin("GET", "state")).body.assignments).toContainEqual({ email: "wait@example.com", plan: "standard" });

    await admin("POST", "access/reject", { id });
    newIsolate();
    const r = await chat(t);
    expect(r.status).toBe(403);
    expect(await errCode(r)).toBe("access_rejected");

    await admin("POST", "access/pending", { id });
    newIsolate();
    expect(await errCode(await chat(t))).toBe("waitlisted");
  });

  it("rejecting takes effect in the same isolate right away (approval cache dropped)", async () => {
    const t = await makeSession(kv, "u_new3", "fast@example.com");
    await admin("POST", "access/approve", { email: "fast@example.com" });
    expect((await chat(t)).status).toBe(200);
    const id = (await accounts()).find(x => x.email === "fast@example.com").id;
    await admin("POST", "access/reject", { id });
    expect(await errCode(await chat(t))).toBe("access_rejected");
  });

  it("assigning a plan in Plans approves the email, even before that person signs in", async () => {
    await admin("PUT", "assignments", { email: "Later@Example.com", plan: "pro" });
    const t = await makeSession(kv, "u_later", "later@example.com");
    expect((await chat(t)).status).toBe(200);
    const rows = (await accounts()).filter(x => x.email === "later@example.com");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ status: "approved", uid: "u_later" });
  });

  it("switching approval off lets everyone in (and remembers them); rejected stay refused", async () => {
    const p = await makeSession(kv, "u_p", "pending@example.com");
    const x = await makeSession(kv, "u_x", "rejected@example.com");
    expect(await errCode(await chat(p))).toBe("waitlisted");
    await admin("POST", "access/reject", { email: "rejected@example.com" });
    expect((await admin("PUT", "settings", { require_approval: false })).status).toBe(200);
    expect((await admin("GET", "state")).body.settings).toEqual({ require_approval: false });
    newIsolate();
    expect((await chat(p)).status).toBe(200);
    const n = await makeSession(kv, "u_n", "brand-new@example.com");
    expect((await chat(n)).status).toBe(200);
    expect(await errCode(await chat(x))).toBe("access_rejected");
    // Back on: the people who got in while it was off keep access.
    await admin("PUT", "settings", { require_approval: true });
    newIsolate();
    expect((await chat(n)).status).toBe(200);
    const z = await makeSession(kv, "u_z", "after@example.com");
    expect(await errCode(await chat(z))).toBe("waitlisted");
  });

  it("approval is on by default; admins are always allowed", async () => {
    expect((await admin("GET", "state")).body.settings).toEqual({ require_approval: true });
    const luke = await makeSession(kv, "u_luke", "luke@example.com"); // in ADMIN_EMAILS
    expect((await chat(luke)).status).toBe(200);
    await admin("POST", "access/reject", { email: "luke@example.com" });
    newIsolate();
    expect((await chat(luke)).status).toBe(200);
  });

  it("refuses bad admin input with a readable error", async () => {
    expect((await admin("PUT", "settings", { require_approval: "yes" })).status).toBe(400);
    expect((await admin("POST", "access/approve", { email: "x@example.com", plan: "gold" })).body.error.message).toContain("No such plan: gold");
    expect((await admin("POST", "access/approve", { id: 999 })).status).toBe(404);
  });
});

describe("grandfathering (migration 0003)", () => {
  it("accounts with a plan or with usage are approved; nobody using Blaze today is locked out", async () => {
    db = new FakeD1((file, sqlite) => {
      if (file !== "0003_access.sql") return;
      sqlite.exec("INSERT INTO plan_assignments (email, plan) VALUES ('paying@example.com', 'pro')");
      sqlite.exec("INSERT INTO usage (uid, day, requests, tokens) VALUES ('u_old', '2026-10-14', 12, 900), ('u_old', '2026-10-15', 3, 10)");
    });
    env = { ...env, DB: db as unknown as D1Database };
    const rows = db.rows("SELECT email, uid, status FROM accounts ORDER BY id");
    expect(rows).toEqual([
      { email: "paying@example.com", uid: null, status: "approved" },
      { email: null, uid: "u_old", status: "approved" },
    ]);
    const paying = await makeSession(kv, "u_pay", "paying@example.com");
    const old = await makeSession(kv, "u_old", "old@example.com");
    const fresh = await makeSession(kv, "u_fresh", "fresh@example.com");
    expect((await chat(paying)).status).toBe(200);
    expect((await chat(old)).status).toBe(200);
    expect(await errCode(await chat(fresh))).toBe("waitlisted");
    // The usage-only row picked up its email on that request.
    expect(db.rows("SELECT email, uid, status FROM accounts WHERE uid = 'u_old'")).toEqual([{ email: "old@example.com", uid: "u_old", status: "approved" }]);
  });

  it("an email row and a usage row for the same person merge into one", async () => {
    db = new FakeD1((file, sqlite) => {
      if (file !== "0003_access.sql") return;
      sqlite.exec("INSERT INTO plan_assignments (email, plan) VALUES ('both@example.com', 'pro')");
      sqlite.exec("INSERT INTO usage (uid, day, requests) VALUES ('u_both', '2026-10-14', 5)");
    });
    env = { ...env, DB: db as unknown as D1Database };
    const t = await makeSession(kv, "u_both", "both@example.com");
    expect((await chat(t)).status).toBe(200);
    expect(db.rows("SELECT email, uid, status FROM accounts")).toEqual([{ email: "both@example.com", uid: "u_both", status: "approved" }]);
  });

  it("a Worker deployed before the migration ran keeps working as before (no lock-out, no 500)", async () => {
    db.sqlite.exec("DROP TABLE accounts; DROP TABLE settings;");
    const t = await makeSession(kv, "u_early", "early@example.com");
    expect((await chat(t)).status).toBe(200);
  });
});

describe("plan assignments (bug: only pro could be added)", () => {
  it("every plan can be assigned, the default plan included, and each shows in the list", async () => {
    // Before 0.3.0, choosing the default plan (basic, the dropdown's first option) deleted the row:
    // "saved", but the email never appeared. Standard and pro were stored.
    for (const [email, plan] of [["a@example.com", "basic"], ["b@example.com", "standard"], ["c@example.com", "pro"]]) {
      const r = await admin("PUT", "assignments", { email, plan });
      expect(r.status).toBe(200);
      expect(r.body).toMatchObject({ ok: true, email, plan, approved: true });
    }
    expect((await admin("GET", "state")).body.assignments).toEqual([
      { email: "a@example.com", plan: "basic" },
      { email: "c@example.com", plan: "pro" },
      { email: "b@example.com", plan: "standard" },
    ]);
  });

  it("a plan added later can be assigned; unknown plans and bad emails get a readable 400", async () => {
    await admin("PUT", "plans/team", { price_usd: 50, daily_requests: 20000, daily_tokens: 0 });
    expect((await admin("PUT", "assignments", { email: "t@example.com", plan: "team" })).status).toBe(200);
    expect((await admin("GET", "state")).body.assignments).toContainEqual({ email: "t@example.com", plan: "team" });
    const bad = await admin("PUT", "assignments", { email: "t@example.com", plan: "gold" });
    expect(bad.status).toBe(400);
    expect(bad.body.error.message).toBe("No such plan: gold");
    expect((await admin("PUT", "assignments", { email: "nope", plan: "pro" })).body.error.message).toContain("Not an email");
  });

  it("remove puts the account back on the default plan (it stays approved)", async () => {
    await admin("PUT", "assignments", { email: "r@example.com", plan: "pro" });
    const r = await admin("PUT", "assignments", { email: "r@example.com", remove: true });
    expect(r.body).toMatchObject({ removed: true, plan: "basic" });
    const s = (await admin("GET", "state")).body;
    expect(s.assignments).toEqual([]);
    expect(s.accounts.find((a: any) => a.email === "r@example.com").status).toBe("approved");
  });

  it("the admin page rebuilds the plan dropdown on every render (new plans appear)", async () => {
    const html = await (await call("GET", "/admin")).text();
    expect(html).not.toContain("if(!sel.options.length)");
    expect(html).toContain("function fillPlanSelect");
    expect(html).toContain("Require approval for new accounts");
  });
});
