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
  // Adding a key runs model discovery (GET …/models); keep chat-call counts about chat calls.
  for (let i = calls.length - 1; i >= 0; i--) if (/\/models(\?|$)/.test(calls[i]!.url)) calls.splice(i, 1);
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
  db.approve("someone@example.com"); // 0.3.0: the waiting list is on by default
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
    expect(await r.json()).toEqual({ ok: true, service: "blaze-gateway", version: "0.3.0" });
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
    db.approve("svc@example.com");
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

  it("blaze_avoid (app v0.26.2): a route that just came back empty goes last, and is never sent upstream", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["groq", "m1"], ["groq", "m2"]]);
    const r = await chat({ blaze_task: "chat", blaze_avoid: ["groq/m1"] });
    await ctx.settle();
    expect(r.headers.get("X-Blaze-Model")).toBe("m2");
    expect(calls[0]!.body.blaze_avoid).toBeUndefined();
    calls.length = 0;
    // Avoiding every route still answers (the avoided ones are tried last, never dropped).
    const r2 = await chat({ blaze_task: "chat", blaze_avoid: ["groq/m1", "groq/m2"] });
    await ctx.settle();
    expect(r2.status).toBe(200);
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

describe("model discovery and the route editor API", () => {
  const geminiList = { models: [
    { name: "models/gemini-flash-latest", displayName: "Gemini Flash Latest", inputTokenLimit: 1048576, supportedGenerationMethods: ["generateContent"] },
    { name: "models/gemini-3-pro-preview", displayName: "Gemini 3 Pro Preview", inputTokenLimit: 1048576, supportedGenerationMethods: ["generateContent"] },
    { name: "models/text-embedding-004", supportedGenerationMethods: ["embedContent"] },
  ] };
  const mistralList = { data: [
    { id: "mistral-small-latest", max_context_length: 131072, capabilities: { completion_chat: true, function_calling: true, vision: true } },
    { id: "codestral-latest", max_context_length: 256000, capabilities: { completion_chat: true, function_calling: true, vision: false } },
  ] };
  beforeEach(() => {
    upstream = (url, init) => {
      if (url.startsWith("https://generativelanguage.googleapis.com/v1beta/models")) return new Response(JSON.stringify(geminiList), { status: 200 });
      if (url === "https://api.mistral.ai/v1/models") return new Response(JSON.stringify(mistralList), { status: 200 });
      if (url.endsWith("/models")) return new Response(JSON.stringify({ data: [] }), { status: 200 });
      return okJSON(JSON.parse(String(init.body)).model);
    };
  });

  it("adding a key finds the provider's models right away; the same key twice isn't duplicated", async () => {
    const r = await admin("POST", "providers/gemini/keys", { key: "AIza-test-key-1234" });
    expect(r.body).toMatchObject({ ok: true, masked: "••••1234", discovery: { ok: true, count: 2 } });
    const g = calls.find(c => c.url.includes("generativelanguage.googleapis.com/v1beta/models"))!;
    expect(g.url).toContain("pageSize=1000");
    const again = await admin("POST", "providers/gemini/keys", { key: "AIza-test-key-1234" });
    expect(again.body).toMatchObject({ ok: true, duplicate: true, id: r.body.id });
    expect(db.rows("SELECT id FROM provider_keys WHERE provider_id = 'gemini'").length).toBe(1);
    const other = await admin("POST", "providers/gemini/keys", { key: "AIza-other-key-9999" });
    expect(other.body.duplicate).toBeUndefined();
    expect(db.rows("SELECT id FROM provider_keys WHERE provider_id = 'gemini'").length).toBe(2);
    const models = await admin("GET", "models");
    expect(models.body.models.map((m: any) => m.id)).toEqual(["gemini-flash-latest"]); // the paid one is hidden
    expect(models.body.models[0].name).toContain("sees images");
    expect(db.rows("SELECT free FROM models WHERE model_id = 'gemini-3-pro-preview'")[0].free).toBe(0);
    const s = await admin("GET", "state");
    expect(s.body.discovery.find((d: any) => d.provider_id === "gemini")).toMatchObject({ ok: 1, count: 2, source: "api" });
    expect(s.body.task_info.vision.needs).toBe("vision");
  });

  it("Find models per provider and for all; Workers AI uses the binding catalogue or the built-in list", async () => {
    await addKey("mistral", "mistral-key-abcdef-9z9z");
    const one = await admin("POST", "providers/mistral/discover");
    expect(one.body).toMatchObject({ ok: true, count: 2, source: "api" });
    const cf = await admin("POST", "providers/workers-ai/discover");
    expect(cf.body.source).toBe("curated"); // FakeAI has no models()
    expect(cf.body.count).toBeGreaterThan(10);
    (ai as any).models = async () => [{ name: "@cf/meta/llama-3.1-8b-instruct-fast", task: { name: "Text Generation" }, properties: [] }];
    expect((await admin("POST", "providers/workers-ai/discover")).body).toMatchObject({ ok: true, count: 1, source: "binding" });
    const groq = await admin("POST", "providers/groq/discover");
    expect(groq.body).toMatchObject({ ok: false, error: "Add a key first" });
    const all = await admin("POST", "discover");
    const ids = all.body.results.map((x: any) => x.provider);
    expect(ids).toContain("mistral");
    expect(ids).toContain("workers-ai");
    expect(ids).not.toContain("groq"); // no key
    upstream = () => new Response("nope", { status: 401 });
    const bad = await admin("POST", "providers/mistral/discover");
    expect(bad.body.ok).toBe(false);
    expect(db.rows("SELECT COUNT(*) AS n FROM models WHERE provider_id = 'mistral'")[0].n).toBe(2); // old list kept
  });

  it("suggests a free lineup and can apply it", async () => {
    await addKey("gemini", "AIza-test-key-1234");
    await addKey("mistral", "mistral-key-abcdef-9z9z");
    await admin("POST", "providers/workers-ai/discover");
    const sug = await admin("POST", "routes/suggest", {});
    expect(sug.body.applied).toBe(false);
    expect(sug.body.routes.chat[0]).toEqual({ provider: "gemini", model: "gemini-flash-latest" });
    expect(sug.body.routes.chat.at(-1).provider).toBe("workers-ai");
    expect(sug.body.routes.code.slice(0, 3).map((c: any) => c.model)).toContain("codestral-latest");
    expect(JSON.stringify(sug.body.routes)).not.toContain("gemini-3-pro-preview");
    const before = (await admin("GET", "state")).body.routes.chat;
    expect(before).not.toEqual(sug.body.routes.chat);
    await admin("POST", "routes/suggest", { apply: true });
    expect((await admin("GET", "state")).body.routes.chat).toEqual(sug.body.routes.chat);
  });

  it("free-only: paid OpenRouter / flagged models can't be saved and are never routed to", async () => {
    expect((await admin("PUT", "routes/chat", { models: [{ provider: "openrouter", model: "openai/gpt-4o" }] })).status).toBe(400);
    await addKey("gemini", "AIza-test-key-1234");
    expect((await admin("PUT", "routes/chat", { models: [{ provider: "gemini", model: "gemini-3-pro-preview" }] })).status).toBe(400);
    // An old route that still names a paid model (saved before discovery) is skipped at request time.
    db.sqlite.prepare("UPDATE task_routes SET models = ?1 WHERE task = 'chat'").run(JSON.stringify([{ provider: "gemini", model: "gemini-3-pro-preview" }, { provider: "workers-ai", model: "@cf/x" }]));
    resetDbCacheForTests();
    calls.length = 0;
    const r = await chat();
    expect(r.headers.get("X-Blaze-Provider")).toBe("workers-ai");
    expect(calls.length).toBe(0);
  });

  it("the cron refreshes stale lists only", async () => {
    await addKey("mistral", "mistral-key-abcdef-9z9z");
    calls.length = 0;
    await worker.scheduled!({ cron: "*/30 * * * *", scheduledTime: Date.now(), noRetry() {} } as any, env, ctx as unknown as ExecutionContext);
    await ctx.settle();
    expect(calls.filter(c => c.url === "https://api.mistral.ai/v1/models").length).toBe(0); // just ran when the key was added
    expect(db.rows("SELECT provider_id FROM discovery").map(r => r.provider_id)).toContain("workers-ai");
    db.sqlite.exec("UPDATE discovery SET ran_at = 1");
    await worker.scheduled!({ cron: "*/30 * * * *", scheduledTime: Date.now(), noRetry() {} } as any, env, ctx as unknown as ExecutionContext);
    await ctx.settle();
    expect(calls.filter(c => c.url === "https://api.mistral.ai/v1/models").length).toBe(1);
  });

  it("old routes without a models table entry keep working (backward compatible)", async () => {
    await addKey("groq", "gsk_test_key_AAAA");
    await setRoute("chat", [["groq", "anything-not-discovered"]]);
    resetDbCacheForTests();
    const r = await chat();
    expect(r.status).toBe(200);
    expect(r.headers.get("X-Blaze-Model")).toBe("anything-not-discovered");
  });
});

describe("plans: assigning any plan (0.3.0 fix)", () => {
  it("stores an explicit row for every plan, the default one included, and the account gets that plan", async () => {
    for (const [email, plan] of [["a@example.com", "basic"], ["b@example.com", "standard"], ["c@example.com", "pro"]]) {
      const r = await admin("PUT", "assignments", { email: email.toUpperCase() + " ", plan });
      expect(r.status).toBe(200);
      expect(r.body).toMatchObject({ ok: true, email, plan, approved: true });
    }
    const s = await admin("GET", "state");
    expect(s.body.assignments).toEqual([
      { email: "a@example.com", plan: "basic" }, { email: "b@example.com", plan: "standard" }, { email: "c@example.com", plan: "pro" },
    ]);
    expect(s.body.access.approved.map((r: any) => r.email).sort()).toEqual(["a@example.com", "b@example.com", "c@example.com", "someone@example.com"]);
    // standard end to end: its limit applies to that account's chats
    await admin("PUT", "plans/standard", { price_usd: 10, daily_requests: 1, daily_tokens: 0 });
    resetDbCacheForTests();
    const tok = await makeSession(kv, "u_b", "b@example.com");
    const one = await call("POST", "/v1/chat/completions", { messages: [{ role: "user", content: "hi" }] }, tok);
    expect(one.status).toBe(200);
    const two = await call("POST", "/v1/chat/completions", { messages: [{ role: "user", content: "hi" }] }, tok);
    expect(two.status).toBe(429);
    expect((await two.json<any>()).error.message).toContain("standard plan");
  });
  it("a plan added later can be assigned; bad plans and emails are refused with a message", async () => {
    await admin("PUT", "plans/team", { price_usd: 5, daily_requests: 10, daily_tokens: 0 });
    expect((await admin("PUT", "assignments", { email: "t@example.com", plan: "team" })).status).toBe(200);
    const bad = await admin("PUT", "assignments", { email: "t@example.com", plan: "nope" });
    expect(bad.status).toBe(400);
    expect(bad.body.error.message).toContain("nope");
    expect((await admin("PUT", "assignments", { email: "not-an-email", plan: "pro" })).status).toBe(400);
    expect((await admin("PUT", "assignments", { email: "t@example.com", plan: "" })).status).toBe(200);
    expect(db.rows("SELECT * FROM plan_assignments WHERE email = 't@example.com'")).toEqual([]);
  });
  it("the admin page rebuilds the plan dropdown on every load and groups assignments by plan", async () => {
    const html = await (await call("GET", "/admin")).text();
    expect(html).not.toContain("if(!sel.options.length)");
    expect(html).toContain("sel.replaceChildren(");
    expect(html).toContain("Waiting list");
  });
});

describe("waiting list (0.3.0)", () => {
  const ask = (tok: string) => call("POST", "/v1/chat/completions", { messages: [{ role: "user", content: "hello" }] }, tok);

  it("an account that isn't approved is recorded as pending and refused with 403 waitlisted", async () => {
    const tok = await makeSession(kv, "u_new", "new@example.com");
    const r = await ask(tok);
    expect(r.status).toBe(403);
    const b = await r.json<any>();
    expect(b.error.code).toBe("waitlisted");
    expect(b.error.message).toBe("You're on the Blaze waiting list. You'll get access as soon as you're approved.");
    expect((await call("GET", "/v1/models", undefined, tok)).status).toBe(403);
    const row = db.rows("SELECT * FROM access WHERE email = 'new@example.com'")[0];
    expect(row).toMatchObject({ status: "pending", uid: "u_new", name: "T", count: 2 });
    expect(row.first_seen).toBeGreaterThan(0);
    expect(row.last_seen).toBeGreaterThanOrEqual(row.first_seen);
    expect(db.rows("SELECT * FROM usage WHERE uid = 'u_new'")).toEqual([]); // not charged
    const s = await admin("GET", "state");
    expect(s.body.access.require_approval).toBe(true);
    expect(s.body.access.pending.map((x: any) => x.email)).toEqual(["new@example.com"]);
  });

  it("approve (with a plan) → allowed on that plan; reject → 403 access_rejected; undo → pending", async () => {
    const tok = await makeSession(kv, "u_new", "new@example.com");
    expect((await ask(tok)).status).toBe(403);
    const ok = await admin("POST", "access", { email: "new@example.com", status: "approved", plan: "standard" });
    expect(ok.body).toMatchObject({ ok: true, status: "approved", plan: "standard" });
    expect((await ask(tok)).status).toBe(200);
    expect(db.rows("SELECT plan FROM plan_assignments WHERE email = 'new@example.com'")).toEqual([{ plan: "standard" }]);
    await admin("POST", "access", { email: "new@example.com", status: "rejected" });
    const r = await ask(tok);
    expect(r.status).toBe(403);
    expect((await r.json<any>()).error.code).toBe("access_rejected");
    let s = await admin("GET", "state");
    expect(s.body.access.rejected.map((x: any) => x.email)).toEqual(["new@example.com"]);
    await admin("POST", "access", { email: "new@example.com", status: "pending" });
    expect((await (await ask(tok)).json<any>()).error.code).toBe("waitlisted");
    s = await admin("GET", "state");
    expect(s.body.access.pending.map((x: any) => x.email)).toEqual(["new@example.com"]);
    // approving without a plan keeps the existing assignment
    await admin("POST", "access", { email: "new@example.com", status: "approved" });
    expect(db.rows("SELECT plan FROM plan_assignments WHERE email = 'new@example.com'")).toEqual([{ plan: "standard" }]);
    expect((await admin("POST", "access", { email: "new@example.com", status: "maybe" })).status).toBe(400);
  });

  it("approving with no plan puts the account on the default plan", async () => {
    const tok = await makeSession(kv, "u_n2", "n2@example.com");
    await ask(tok);
    const r = await admin("POST", "access", { email: "n2@example.com", status: "approved" });
    expect(r.body.plan).toBe("basic");
    expect((await ask(tok)).status).toBe(200);
  });

  it("assigning a plan in the plans list also approves", async () => {
    const tok = await makeSession(kv, "u_n3", "n3@example.com");
    expect((await ask(tok)).status).toBe(403);
    await admin("PUT", "assignments", { email: "n3@example.com", plan: "standard" });
    expect((await ask(tok)).status).toBe(200);
  });

  it("turning 'Require approval' off lets new and pending accounts in; rejected stay blocked", async () => {
    const tok = await makeSession(kv, "u_new", "new@example.com");
    const bad = await makeSession(kv, "u_bad", "bad@example.com");
    await admin("POST", "access", { email: "bad@example.com", status: "rejected" });
    expect((await ask(tok)).status).toBe(403);
    expect((await admin("PUT", "settings", { require_approval: false })).status).toBe(200);
    expect((await ask(tok)).status).toBe(200);
    expect((await ask(bad)).status).toBe(403);
    expect((await admin("GET", "state")).body.access.require_approval).toBe(false);
    expect((await admin("PUT", "settings", { require_approval: "no" })).status).toBe(400);
    await admin("PUT", "settings", { require_approval: true });
    expect((await ask(await makeSession(kv, "u_later", "later@example.com"))).status).toBe(403);
  });

  it("admins are always allowed", async () => {
    const luke = await makeSession(kv, "u_luke", "luke@example.com");
    expect((await ask(luke)).status).toBe(200);
    expect(db.rows("SELECT * FROM access WHERE email = 'luke@example.com'")).toEqual([]);
  });

  it("the migration grandfathers accounts with an assignment or any usage history", async () => {
    resetDbCacheForTests();
    const old = makeEnv({}, { before: "0003" });
    old.db.sqlite.exec("INSERT INTO plan_assignments (email, plan, updated_at) VALUES ('pro@example.com', 'pro', 1)");
    old.db.sqlite.exec("INSERT INTO usage (uid, day, requests, tokens) VALUES ('u_old', '2026-10-10', 3, 100)");
    old.db.migrate();
    expect(old.db.rows("SELECT email, status, decided_by FROM access")).toEqual([{ email: "pro@example.com", status: "approved", decided_by: "migration" }]);
    expect(old.db.rows("SELECT value FROM settings WHERE key = 'require_approval'")).toEqual([{ value: "1" }]);
    env = old.env; db = old.db; kv = old.kv;
    const pro = await makeSession(kv, "u_pro", "pro@example.com");
    const oldUser = await makeSession(kv, "u_old", "old@example.com");
    const fresh = await makeSession(kv, "u_fresh", "fresh@example.com");
    expect((await ask(pro)).status).toBe(200);
    expect((await ask(oldUser)).status).toBe(200);
    expect(db.rows("SELECT status, uid, decided_by FROM access WHERE email = 'old@example.com'")).toEqual([{ status: "approved", uid: "u_old", decided_by: "usage history" }]);
    expect((await ask(fresh)).status).toBe(403);
  });

  it("without migration 0003 the gateway keeps working like 0.2.x and the admin page warns", async () => {
    resetDbCacheForTests();
    const old = makeEnv({}, { before: "0003" });
    env = old.env; db = old.db; kv = old.kv;
    const tok = await makeSession(kv, "u_x", "x@example.com");
    expect((await ask(tok)).status).toBe(200);
    const s = await admin("GET", "state");
    expect(s.body.access.available).toBe(false);
    expect(s.body.warnings.join(" ")).toContain("0003_waitlist.sql");
  });
});
