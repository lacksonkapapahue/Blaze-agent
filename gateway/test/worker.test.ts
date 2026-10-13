// End to end through the Worker's fetch(), with D1 on node:sqlite and the network mocked.

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import worker from "../src/index";
import { resetDbCacheForTests } from "../src/db";
import { clearAuthCache } from "../src/auth";
import { resetRotationForTests } from "../src/chat";
import { FakeCtx, makeEnv, makeSession, sse, type FakeAI, type FakeD1, type FakeKV } from "./helpers";
import type { Env } from "../src/env";

const BASE = "https://blaze-gateway.blaze-app.workers.dev";
let env: Env, db: FakeD1, kv: FakeKV, ai: FakeAI, ctx: FakeCtx;
let userToken: string;
const ADMIN = "admin-token-0123456789abcdef";

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;
let upstream: Handler;
const calls: { url: string; auth: string; body: any }[] = [];

async function call(method: string, path: string, body?: unknown, token?: string) {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await worker.fetch(new Request(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), env, ctx as unknown as ExecutionContext);
  return res;
}

async function admin(method: string, path: string, body?: unknown) {
  const r = await call(method, "/admin/api/" + path, body, ADMIN);
  await ctx.settle();
  return { status: r.status, body: await r.json<any>() };
}

async function addKey(provider: string, key: string) {
  const r = await admin("POST", `providers/${provider}/keys`, { key });
  expect(r.status).toBe(200);
  return r.body;
}

async function setRoute(task: string, models: [string, string][]) {
  const r = await admin("PUT", `routes/${task}`, { models: models.map(([provider, model]) => ({ provider, model })) });
  expect(r.status).toBe(200);
}

const chat = (extra: Record<string, unknown> = {}) => call("POST", "/v1/chat/completions", { model: "blaze-auto", messages: [{ role: "user", content: "my secret diary entry 7731" }], ...extra }, userToken);

function okJSON(model: string, text = "hi") {
  return new Response(JSON.stringify({ id: "x", object: "chat.completion", model, choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }], usage: { total_tokens: 42 } }), { status: 200, headers: { "Content-Type": "application/json" } });
}

beforeEach(async () => {
  resetDbCacheForTests();
  clearAuthCache();
  resetRotationForTests();
  ({ env, db, kv, ai } = makeEnv());
  ctx = new FakeCtx();
  userToken = await makeSession(kv, "u_user1", "someone@example.com");
  calls.length = 0;
  upstream = (url, init) => okJSON(JSON.parse(String(init.body)).model);
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input instanceof Request ? input.url : input);
    calls.push({ url, auth: String((init.headers as Record<string, string>)?.Authorization || ""), body: init.body ? JSON.parse(String(init.body)) : null });
    return upstream(url, init);
  });
});
afterEach(() => vi.unstubAllGlobals());

describe("basics", () => {
  it("health", async () => {
    const r = await call("GET", "/health");
    expect(await r.json()).toEqual({ ok: true, service: "blaze-gateway", version: "0.1.0" });
  });
  it("app endpoints need a live Blaze session", async () => {
    expect((await call("GET", "/v1/models")).status).toBe(401);
    expect((await call("GET", "/v1/models", undefined, "bz_" + "n".repeat(43))).status).toBe(401);
    expect((await call("GET", "/v1/models", undefined, "sk-openai-style")).status).toBe(401);
    const r = await call("GET", "/v1/models", undefined, userToken);
    expect(r.status).toBe(200);
    const ids = (await r.json<any>()).data.map((m: any) => m.id);
    expect(ids).toEqual(["blaze-auto", "blaze-chat", "blaze-quick", "blaze-code", "blaze-vision", "blaze-long", "blaze-agent"]);
  });
  it("a session that blaze-auth revoked (not in the user's list) is refused", async () => {
    const u = await kv.get("user:u_user1", "json");
    await kv.put("user:u_user1", JSON.stringify({ ...u, sessions: [] }));
    clearAuthCache();
    expect((await call("GET", "/v1/models", undefined, userToken)).status).toBe(401);
  });
  it("falls back to blaze-auth /v1/me through the AUTH service binding", async () => {
    const me = vi.fn(async () => new Response(JSON.stringify({ user: { id: "u_svc", email: "svc@example.com" } }), { status: 200 }));
    env = { ...env, AUTH_KV: undefined, AUTH: { fetch: me } as unknown as Fetcher };
    expect((await call("GET", "/v1/models", undefined, "bz_" + "s".repeat(43))).status).toBe(200);
    expect(me).toHaveBeenCalledOnce();
  });
});

describe("chat", () => {
  it("routes to the first model with a key and passes the answer through", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["gemini", "gemini-flash-latest"], ["groq", "llama-3.3-70b-versatile"], ["workers-ai", "@cf/x"]]);
    const r = await chat({ blaze_task: "chat" });
    await ctx.settle();
    expect(r.status).toBe(200);
    expect(r.headers.get("X-Blaze-Provider")).toBe("groq"); // gemini has no key → skipped
    expect(r.headers.get("X-Blaze-Task")).toBe("chat");
    expect((await r.json<any>()).choices[0].message.content).toBe("hi");
    expect(calls[0]!.url).toBe("https://api.groq.com/openai/v1/chat/completions");
    expect(calls[0]!.auth).toBe("Bearer gsk_test_key_AAAA");
    expect(calls[0]!.body.model).toBe("llama-3.3-70b-versatile");
    expect(calls[0]!.body.blaze_task).toBeUndefined();
    await ctx.settle();
    expect(db.rows("SELECT requests, tokens FROM usage")).toEqual([{ requests: 1, tokens: 42 }]);
  });

  it("fails over on 429 / 5xx / timeout, cools those models, and skips them next time", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await addKey("cerebras", "csk_test_key_BBBB");
    await setRoute("chat", [["groq", "m1"], ["cerebras", "m2"], ["groq", "m3"]]);
    upstream = (url, init) => {
      const m = JSON.parse(String(init.body)).model;
      if (m === "m1") return new Response('{"error":{"message":"Rate limit reached"}}', { status: 429, headers: { "retry-after": "30" } });
      if (m === "m2") return new Response("overloaded", { status: 503 });
      return okJSON(m);
    };
    const r = await chat();
    await ctx.settle();
    expect(r.headers.get("X-Blaze-Model")).toBe("m3");
    const st = db.rows("SELECT provider_id, model, state, until > 0 AS cooling, reason FROM model_state ORDER BY model");
    expect(st).toEqual([
      { provider_id: "groq", model: "m1", state: "limited", cooling: 1, reason: "rate_limit" },
      { provider_id: "cerebras", model: "m2", state: "failed", cooling: 1, reason: "server" },
      { provider_id: "groq", model: "m3", state: "ok", cooling: 0, reason: "" },
    ]);
    calls.length = 0;
    resetDbCacheForTests();
    const r2 = await chat();
    await ctx.settle();
    expect(r2.status).toBe(200);
    expect(calls.map(c => c.body.model)).toEqual(["m3"]); // cooling models never tried
  });

  it("rotates keys: a rejected key cools only itself; a 429 tries the provider's next key", async () => {
    await addKey("groq", "gsk_bad_key_1111");
    await addKey("groq", "gsk_good_key_2222");
    await setRoute("chat", [["groq", "m1"]]);
    upstream = (url, init) => {
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth.endsWith("1111") ? new Response("Invalid API Key", { status: 401 }) : okJSON("m1");
    };
    const r = await chat();
    await ctx.settle();
    expect(r.status).toBe(200);
    const keys = db.rows("SELECT last4, cooling_until > 0 AS cooling FROM provider_keys ORDER BY id");
    expect(keys).toEqual([{ last4: "1111", cooling: 1 }, { last4: "2222", cooling: 0 }]);
    expect(db.rows("SELECT * FROM model_state WHERE until > 0")).toEqual([]); // the model itself is fine

    let n = 0;
    const seen: string[] = [];
    upstream = (url, init) => {
      seen.push((init.headers as Record<string, string>).Authorization);
      return n++ === 0 ? new Response("rate limit", { status: 429 }) : okJSON("m1");
    };
    await admin("PATCH", "keys/1", { clear_cooldown: true });
    calls.length = 0;
    const r2 = await chat();
    await ctx.settle();
    expect(r2.status).toBe(200);
    expect(calls.length).toBe(2);
    expect(new Set(seen).size).toBe(2); // the retry used the other key
  });

  it("streams SSE through untouched", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["groq", "m1"]]);
    upstream = () => sse(['{"choices":[{"delta":{"content":"Hel"}}]}', '{"choices":[{"delta":{"content":"lo"}}],"usage":{"total_tokens":12}}']);
    const r = await chat({ stream: true });
    expect(r.headers.get("Content-Type")).toBe("text/event-stream");
    const text = await r.text();
    expect(text).toContain('"Hel"');
    expect(text.trim().endsWith("data: [DONE]")).toBe(true);
    await ctx.settle();
    expect(db.rows("SELECT tokens FROM usage")).toEqual([{ tokens: 12 }]);
  });

  it("falls back to Workers AI (binding) and converts its stream to OpenAI chunks", async () => {
    await setRoute("chat", [["groq", "m1"], ["workers-ai", "@cf/meta/llama-3.3-70b-instruct-fp8-fast"]]);
    const r = await chat({ stream: true });
    expect(r.headers.get("X-Blaze-Provider")).toBe("workers-ai");
    const text = await r.text();
    const deltas = text.split("\n\n").filter(l => l.startsWith("data: {")).map(l => JSON.parse(l.slice(6)));
    expect(deltas[0].choices[0].delta.role).toBe("assistant");
    expect(deltas.map(d => d.choices[0].delta.content || "").join("")).toBe("Hello from Workers AI");
    expect(deltas[deltas.length - 1].choices[0].finish_reason).toBe("stop");
    expect(text.trim().endsWith("data: [DONE]")).toBe(true);
    expect(ai.calls[0]!.inputs.max_tokens).toBe(2048);
  });

  it("Workers AI tool calls come back in OpenAI shape", async () => {
    ai.run = async () => ({ response: null, tool_calls: [{ name: "get_weather", arguments: { city: "Ndola" } }] });
    const r = await call("POST", "/v1/chat/completions", { messages: [{ role: "user", content: "weather?" }], tools: [{ type: "function", function: { name: "get_weather", parameters: {} } }] }, userToken);
    expect(r.headers.get("X-Blaze-Task")).toBe("agent;inferred");
    const b = await r.json<any>();
    expect(b.choices[0].finish_reason).toBe("tool_calls");
    expect(b.choices[0].message.tool_calls[0].function).toEqual({ name: "get_weather", arguments: '{"city":"Ndola"}' });
  });

  it("every model busy → 503 with Retry-After, and the request isn't charged", async () => {
    ai.fail = "3040: Capacity temporarily exceeded";
    const r = await chat();
    await ctx.settle();
    expect(r.status).toBe(503);
    expect(Number(r.headers.get("Retry-After"))).toBeGreaterThan(0);
    expect((await r.json<any>()).error.type).toBe("blaze_models_busy");
    expect(db.rows("SELECT requests FROM usage")).toEqual([{ requests: 0 }]);
  });

  it("two 400s in a row are the request's fault: returned to the client, no model cooled", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["groq", "m1"], ["groq", "m2"], ["groq", "m3"]]);
    upstream = () => new Response('{"error":{"message":"bad field foo"}}', { status: 400 });
    const r = await chat();
    await ctx.settle();
    expect(r.status).toBe(400);
    expect(calls.length).toBe(2);
    expect(db.rows("SELECT * FROM model_state WHERE until > 0")).toEqual([]);
  });

  it("enforces the plan's daily quota (basic by default, assignable in admin)", async () => {
    await admin("PUT", "plans/basic", { price_usd: 0, daily_requests: 2, daily_tokens: 0 });
    resetDbCacheForTests();
    expect((await chat()).status).toBe(200);
    const second = await chat();
    expect(second.headers.get("X-RateLimit-Remaining-Requests")).toBe("0");
    const third = await chat();
    expect(third.status).toBe(429);
    expect((await third.json<any>()).error.message).toContain("basic plan");
    await admin("PUT", "assignments", { email: "SOMEONE@example.com", plan: "standard" });
    expect((await chat()).status).toBe(200);
  });

  it("never stores message content", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    upstream = () => new Response("oops my secret diary entry 7731", { status: 500 });
    await chat({ blaze_task: "chat" });
    await chat({ blaze_task: "code" });
    await ctx.settle();
    const dump = db.dump();
    expect(dump).not.toContain("7731");
    expect(dump).not.toContain("diary");
    expect(dump).not.toContain("gsk_test_key_AAAA");
  });
});

describe("admin", () => {
  it("serves the page with a strict CSP", async () => {
    const r = await call("GET", "/admin");
    expect(r.headers.get("Content-Type")).toContain("text/html");
    expect(r.headers.get("Content-Security-Policy")).toContain("script-src 'nonce-");
    expect(await r.text()).toContain("BLAZE GATEWAY");
  });
  it("only ADMIN_EMAILS accounts or the admin token get in", async () => {
    expect((await call("GET", "/admin/api/state")).status).toBe(401);
    expect((await call("GET", "/admin/api/state", undefined, userToken)).status).toBe(401);
    expect((await call("GET", "/admin/api/state", undefined, "admin-token-wrong-wrong-wrong")).status).toBe(401);
    const luke = await makeSession(kv, "u_luke", "luke@example.com");
    expect((await call("GET", "/admin/api/me", undefined, luke)).status).toBe(200);
    expect((await call("GET", "/admin/api/me", undefined, ADMIN)).status).toBe(200);
  });
  it("account sign-in goes through blaze-auth and is limited to ADMIN_EMAILS", async () => {
    const auth = vi.fn(async () => new Response(JSON.stringify({ token: "bz_" + "l".repeat(43) }), { status: 200 }));
    env = { ...env, AUTH: { fetch: auth } as unknown as Fetcher };
    const bad = await call("POST", "/admin/api/login", { email: "someone@example.com", password: "pw" });
    expect(bad.status).toBe(403);
    expect(auth).not.toHaveBeenCalled();
    const ok = await call("POST", "/admin/api/login", { email: "Luke@example.com", password: "pw" });
    expect(ok.status).toBe(200);
    expect((await ok.json<any>()).token).toMatch(/^bz_/);
  });
  it("keys are encrypted at rest and only ever shown masked", async () => {
    const r = await addKey("mistral", "mistral-key-abcdef-9z9z");
    expect(r.masked).toBe("••••9z9z");
    const row = db.rows("SELECT enc, last4 FROM provider_keys")[0];
    expect(row.enc.startsWith("v1.")).toBe(true);
    expect(row.enc).not.toContain("abcdef");
    const s = await admin("GET", "state");
    expect(JSON.stringify(s.body)).not.toContain("mistral-key-abcdef");
    expect(s.body.providers.find((p: any) => p.id === "mistral").keys[0].masked).toBe("••••9z9z");
  });
  it("adds / edits / disables providers; refuses non-https base URLs", async () => {
    expect((await admin("POST", "providers", { id: "together", name: "Together", base_url: "http://api.together.xyz/v1", type: "openai" })).status).toBe(400);
    expect((await admin("POST", "providers", { id: "together", name: "Together", base_url: "https://api.together.xyz/v1/", type: "openai" })).status).toBe(200);
    expect((await admin("PUT", "providers/together", { enabled: false })).status).toBe(200);
    const s = await admin("GET", "state");
    const p = s.body.providers.find((x: any) => x.id === "together");
    expect(p).toMatchObject({ base_url: "https://api.together.xyz/v1", enabled: false });
    expect((await admin("DELETE", "providers/workers-ai")).status).toBe(400);
  });
  it("disabled providers are skipped in routing", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["groq", "m1"], ["workers-ai", "@cf/x"]]);
    await admin("PUT", "providers/groq", { enabled: false });
    resetDbCacheForTests();
    const r = await chat();
    expect(r.headers.get("X-Blaze-Provider")).toBe("workers-ai");
  });
  it("edits and resets task routes, edits plans", async () => {
    await setRoute("code", [["cerebras", "gpt-oss-120b"]]);
    let s = await admin("GET", "state");
    expect(s.body.routes.code).toEqual([{ provider: "cerebras", model: "gpt-oss-120b" }]);
    await admin("POST", "routes/reset");
    s = await admin("GET", "state");
    expect(s.body.routes.code.length).toBeGreaterThan(1);
    expect((await admin("PUT", "routes/bogus", { models: [] })).status).toBe(404);
    await admin("PUT", "plans/pro", { price_usd: 20, daily_requests: 9999, daily_tokens: 0 });
    s = await admin("GET", "state");
    expect(s.body.plans.map((p: any) => p.id)).toEqual(["basic", "standard", "pro"]);
    expect(s.body.plans.find((p: any) => p.id === "pro").daily_requests).toBe(9999);
    expect(s.body.default_plan).toBe("basic");
  });
  it("health shows cooldowns, today's counts, and test buttons work", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["groq", "m1"], ["workers-ai", "@cf/x"]]);
    upstream = () => new Response("rate limit", { status: 429 });
    await chat();
    await ctx.settle();
    let s = await admin("GET", "state");
    const h = s.body.health.find((x: any) => x.model === "m1");
    expect(h.cooling_until).toBeGreaterThan(Date.now());
    expect(h.fail_today).toBe(1);
    expect(s.body.health.find((x: any) => x.model === "@cf/x").ok_today).toBe(1);
    await admin("POST", "cooldowns/clear", { provider: "groq", model: "m1" });
    s = await admin("GET", "state");
    expect(s.body.health.find((x: any) => x.model === "m1").cooling_until).toBe(0);

    upstream = url => (url.endsWith("/models") ? new Response(JSON.stringify({ data: [{ id: "a" }, { id: "b" }] }), { status: 200 }) : okJSON("m1"));
    const t = await admin("POST", "keys/1/test");
    expect(t.body).toMatchObject({ ok: true, models: 2 });
    const tm = await admin("POST", "test-model", { provider: "groq", model: "m1" });
    expect(tm.body.ok).toBe(true);
  });
  it("warns when a secret is missing", async () => {
    env = { ...env, KEY_ENCRYPTION_SECRET: undefined };
    const s = await admin("GET", "state");
    expect(s.body.warnings.join(" ")).toContain("KEY_ENCRYPTION_SECRET");
    expect((await call("POST", `/admin/api/providers/groq/keys`, { key: "gsk_whatever_1234" }, ADMIN)).status).toBe(503);
  });
});
