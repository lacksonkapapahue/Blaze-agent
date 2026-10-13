import { describe, it, expect } from "vitest";
import { decryptKey, encryptKey, last4, masked } from "../src/crypto";
import {
  applyOutcome, classify, durationMs, inferTask, markOk, normalizeTask, pickTask, planCandidates, retryAfterMs, taskFromModel,
  LONG_CHARS, type Mark,
} from "../src/router";
import { judge, planFor } from "../src/quota";
import { DEFAULT_PLANS, DEFAULT_ROUTES, DEFAULT_PLAN, WORKERS_AI } from "../src/defaults";
import { SECRET } from "./helpers";

describe("crypto", () => {
  it("round-trips and never stores the key in the clear", async () => {
    const blob = await encryptKey(SECRET, "groq", "gsk_supersecret1234");
    expect(blob.startsWith("v1.")).toBe(true);
    expect(blob).not.toContain("supersecret");
    expect(await decryptKey(SECRET, "groq", blob)).toBe("gsk_supersecret1234");
  });
  it("uses a fresh nonce every time", async () => {
    expect(await encryptKey(SECRET, "groq", "k1234567")).not.toBe(await encryptKey(SECRET, "groq", "k1234567"));
  });
  it("binds the provider (AAD) and the secret", async () => {
    const blob = await encryptKey(SECRET, "groq", "gsk_abcdefgh");
    await expect(decryptKey(SECRET, "mistral", blob)).rejects.toThrow();
    await expect(decryptKey(SECRET + "x", "groq", blob)).rejects.toThrow();
  });
  it("refuses a missing or short secret", async () => {
    await expect(encryptKey(undefined, "groq", "k")).rejects.toThrow(/KEY_ENCRYPTION_SECRET/);
    await expect(encryptKey("short", "groq", "k")).rejects.toThrow(/KEY_ENCRYPTION_SECRET/);
  });
  it("masks to the last 4", () => {
    expect(masked(last4("sk-or-v1-abcdef9876"))).toBe("••••9876");
  });
});

describe("task tags", () => {
  it("normalizes tags and aliases", () => {
    expect(normalizeTask("agent/tools")).toBe("agent");
    expect(normalizeTask("TOOLS")).toBe("agent");
    expect(normalizeTask("vision")).toBe("vision");
    expect(normalizeTask("nope")).toBeNull();
    expect(normalizeTask(5)).toBeNull();
  });
  it("reads blaze-<task> model names", () => {
    expect(taskFromModel("blaze-code")).toBe("code");
    expect(taskFromModel("blaze-auto")).toBeNull();
    expect(taskFromModel("gpt-4o")).toBeNull();
  });
  it("infers: images → vision, tools → agent, long → long, code → code, else chat", () => {
    const img = [{ role: "user", content: [{ type: "text", text: "what's this" }, { type: "image_url", image_url: { url: "data:image/png;base64,AA" } }] }];
    expect(inferTask({ messages: img, tools: [{ type: "function" }] })).toBe("vision");
    expect(inferTask({ messages: [{ role: "user", content: "hi" }], tools: [{ type: "function", function: { name: "x" } }] })).toBe("agent");
    expect(inferTask({ messages: [{ role: "user", content: "a".repeat(LONG_CHARS + 1) }] })).toBe("long");
    expect(inferTask({ messages: [{ role: "user", content: "fix this:\n```js\nconst a = 1\n```" }] })).toBe("code");
    expect(inferTask({ messages: [{ role: "user", content: "how was your day?" }] })).toBe("chat");
    expect(inferTask({})).toBe("chat");
  });
  it("tag beats model beats inference", () => {
    expect(pickTask({ blaze_task: "quick", model: "blaze-code", messages: [] })).toEqual({ task: "quick", source: "tag" });
    expect(pickTask({ model: "blaze-code", messages: [] })).toEqual({ task: "code", source: "model" });
    expect(pickTask({ blaze_task: "bogus", messages: [{ role: "user", content: "hi" }] })).toEqual({ task: "chat", source: "inferred" });
  });
});

describe("planCandidates", () => {
  const order = [
    { provider: "gemini", model: "g" }, { provider: "groq", model: "a" }, { provider: "groq", model: "b" },
    { provider: "off", model: "x" }, { provider: "groq", model: "a" }, { provider: WORKERS_AI, model: "cf" },
  ];
  const usable = (p: string) => p !== "off";
  const now = 1_000_000_000;
  it("keeps admin order, drops unusable providers and duplicates", () => {
    const p = planCandidates(order, usable, new Map(), now);
    expect(p.ready.map(c => c.model)).toEqual(["g", "a", "b", "cf"]);
    expect(p.skipped.map(c => c.provider)).toEqual(["off"]);
  });
  it("skips cooling models and whole providers, and reports when they free up", () => {
    const marks = new Map<string, Mark>([
      ["gemini|g", { state: "limited", until: now + 5000, reason: "rate_limit", okAt: 0, failAt: now }],
      ["groq|*", { state: "limited", until: now + 9000, reason: "provider_limit", okAt: 0, failAt: now }],
    ]);
    const p = planCandidates(order, usable, marks, now);
    expect(p.ready.map(c => c.model)).toEqual(["cf"]);
    expect(p.cooling.map(x => x.until)).toEqual([now + 5000, now + 9000, now + 9000]);
  });
  it("moves a cooled-down hard failure to the back until it works again", () => {
    const marks = new Map<string, Mark>([["gemini|g", { state: "failed", until: now - 1, reason: "server", okAt: 0, failAt: now - 60_000 }]]);
    expect(planCandidates(order, usable, marks, now).ready.map(c => c.model)).toEqual(["a", "b", "cf", "g"]);
    markOk(marks, { provider: "gemini", model: "g" }, now);
    expect(planCandidates(order, usable, marks, now).ready[0]!.model).toBe("g");
  });
  it("every default route ends with the Workers AI fallback", () => {
    for (const list of Object.values(DEFAULT_ROUTES)) expect(list[list.length - 1]!.provider).toBe(WORKERS_AI);
  });
});

describe("classify", () => {
  it("429 cools the model for the provider's hint (clamped)", () => {
    expect(classify("groq", 429, "Rate limit reached", 20_000)).toEqual({ kind: "rate_limit", coolMs: 20_000, scope: "model" });
    expect(classify("groq", 429, "", 10).coolMs).toBe(3_000);
    expect(classify("groq", 429, "", null).coolMs).toBe(60_000);
  });
  it("provider-wide caps rest the whole provider", () => {
    expect(classify("openrouter", 429, "Rate limit exceeded: free-models-per-day").scope).toBe("provider");
    expect(classify("x", 402, "Payment required").scope).toBe("provider");
    expect(classify(WORKERS_AI, 429, "4006: you have used up your daily free allocation of 10,000 neurons").scope).toBe("provider");
  });
  it("401 / bad key only cools the key", () => {
    expect(classify("groq", 401, "Invalid API Key").scope).toBe("key");
    expect(classify("gemini", 400, "API key not valid. Please pass a valid API key.").scope).toBe("key");
  });
  it("404 = gone for a day, 5xx and timeouts cool briefly, 413 and 400 never cool", () => {
    expect(classify("groq", 404, "model_not_found")).toMatchObject({ kind: "gone", coolMs: 86_400_000 });
    expect(classify("groq", 503, "overloaded")).toMatchObject({ kind: "server", coolMs: 120_000, scope: "model" });
    expect(classify("groq", 0, "timeout")).toMatchObject({ kind: "timeout", scope: "model" });
    expect(classify("groq", 413, "too large").scope).toBe("none");
    expect(classify("groq", 400, "messages[0].content: invalid").scope).toBe("none");
  });
  it("applyOutcome writes a TTL mark at the right scope", () => {
    const marks = new Map<string, Mark>();
    const c = { provider: "groq", model: "a" };
    expect(applyOutcome(marks, c, classify("groq", 400, "bad"), 1000)).toBeNull();
    expect(applyOutcome(marks, c, classify("groq", 503, ""), 1000)!.key).toBe("groq|a");
    expect(marks.get("groq|a")).toMatchObject({ state: "failed", until: 121_000 });
    expect(applyOutcome(marks, c, classify("x", 402, ""), 1000)!.key).toBe("groq|*");
  });
  it("reads Retry-After and x-ratelimit-reset headers", () => {
    expect(retryAfterMs(new Headers({ "retry-after": "7" }))).toBe(7000);
    expect(retryAfterMs(new Headers({ "x-ratelimit-reset-requests": "1m30s" }))).toBe(90_000);
    expect(retryAfterMs(new Headers())).toBeNull();
    expect(durationMs("250ms")).toBe(250);
    expect(durationMs("2.5s")).toBe(2500);
    expect(durationMs("soon")).toBeNull();
  });
});

describe("quota", () => {
  const basic = DEFAULT_PLANS.find(p => p.id === "basic")!;
  it("plans are basic $0, standard $10, pro $20; new accounts are basic", () => {
    expect(DEFAULT_PLANS.map(p => [p.id, p.price_usd])).toEqual([["basic", 0], ["standard", 10], ["pro", 20]]);
    expect(DEFAULT_PLAN).toBe("basic");
    expect(planFor("new@x.com", new Map(), DEFAULT_PLANS).id).toBe("basic");
    expect(planFor("Pay@X.com", new Map([["pay@x.com", "pro"]]), DEFAULT_PLANS).id).toBe("pro");
    expect(planFor("a@x.com", new Map([["a@x.com", "deleted-plan"]]), DEFAULT_PLANS).id).toBe("basic");
  });
  it("allows up to the daily limit, then refuses until UTC midnight", () => {
    const t = Date.UTC(2026, 9, 13, 22, 0, 0);
    expect(judge(basic, basic.daily_requests, 0, t)).toMatchObject({ ok: true, remaining: 0 });
    expect(judge(basic, basic.daily_requests + 1, 0, t)).toMatchObject({ ok: false, reason: "requests", resetMs: 2 * 3600_000 });
    expect(judge(basic, 1, basic.daily_tokens, t)).toMatchObject({ ok: false, reason: "tokens" });
    const pro = DEFAULT_PLANS.find(p => p.id === "pro")!;
    expect(judge(pro, 1, 10 ** 9, t).ok).toBe(true); // 0 = no token cap
  });
});
