// /admin/api/*: providers, keys, task routes, plans, health. Every route needs requireAdmin.
// Keys go in once and come back only as "••••" + last 4.

import { adminEmails, requireAdmin } from "./auth";
import { discoverAll, discoverProvider, isRoutable, loadModels, suggestLineup, TASK_INFO } from "./catalog";
import { encryptKey, last4, masked, MIN_SECRET_LEN, decryptKey } from "./crypto";
import { ensureSeeded, invalidateConfig, loadConfig, loadMarks, saveMarkStmt, seed } from "./db";
import { DEFAULT_PLAN, DEFAULT_ROUTES, WORKERS_AI, type Candidate } from "./defaults";
import type { Env } from "./env";
import { VERSION } from "./env";
import { callOpenAI, callWorkersAI, testKey, upstreamBody } from "./providers";
import { classify, markOk, applyOutcome, normalizeTask, TASKS, type Mark } from "./router";
import { HttpError, json, readJSON, utcDay } from "./util";

const ID_RE = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function str(v: unknown, max = 200): string {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}

function intIn(v: unknown, lo: number, hi: number): number {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n) || n < lo || n > hi) throw new HttpError(400, "bad_value", `Expected a number from ${lo} to ${hi}`);
  return n;
}

function checkBaseUrl(u: string, type: string): string {
  if (type === "workers-ai") return "binding:AI";
  let url: URL;
  try { url = new URL(u); } catch { throw new HttpError(400, "bad_url", "Base URL must be a full https:// URL"); }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) throw new HttpError(400, "bad_url", "Base URL must be https:// with no query, fragment or credentials");
  return url.toString().replace(/\/+$/, "");
}

/** POST /admin/api/login: Blaze email + password (through blaze-auth), or the ADMIN_TOKEN. */
async function login(req: Request, env: Env): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const token = str(b.token, 500);
  if (token) return json({ token }); // the page then calls /admin/api/me with it
  const email = str(b.email, 254).toLowerCase();
  const password = typeof b.password === "string" ? b.password : "";
  if (!email || !password) throw new HttpError(400, "missing", "Email and password");
  if (!adminEmails(env).includes(email)) throw new HttpError(403, "not_admin", "That account isn't an admin of this gateway");
  if (!env.AUTH) throw new HttpError(503, "auth_not_bound", "Account sign-in needs the AUTH service binding; use the admin token instead");
  const res = await env.AUTH.fetch("https://blaze-auth/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": req.headers.get("CF-Connecting-IP") || "" },
    body: JSON.stringify({ email, password, client: "Blaze Gateway admin" }),
  });
  const out = await res.json<{ token?: string; error?: string }>().catch(() => ({} as { token?: string; error?: string }));
  if (!res.ok || !out.token) throw new HttpError(res.status === 429 ? 429 : 401, out.error || "invalid_credentials", res.status === 429 ? "Too many tries, wait a few minutes" : "Wrong email or password");
  return json({ token: out.token });
}

async function logout(req: Request, env: Env): Promise<Response> {
  const auth = req.headers.get("Authorization") || "";
  if (env.AUTH && auth.includes("bz_")) await env.AUTH.fetch("https://blaze-auth/v1/logout", { method: "POST", headers: { Authorization: auth } }).catch(() => undefined);
  return json({ ok: true });
}

async function state(env: Env): Promise<Response> {
  invalidateConfig();
  const cfg = await loadConfig(env.DB);
  const marks = await loadMarks(env.DB);
  const day = utcDay();
  const disc = await env.DB.prepare("SELECT provider_id, ran_at, ok, count, source, error FROM discovery").all<any>().catch(() => ({ results: [] as any[] }));
  const [stats, allState, usage] = await env.DB.batch([
    env.DB.prepare("SELECT provider_id, model, ok, fail, ms_total FROM model_stats WHERE day = ?1").bind(day),
    env.DB.prepare("SELECT provider_id, model, state, until, reason, ok_at, fail_at FROM model_state"),
    env.DB.prepare("SELECT COUNT(*) AS users, COALESCE(SUM(requests), 0) AS requests, COALESCE(SUM(tokens), 0) AS tokens FROM usage WHERE day = ?1").bind(day),
  ]);
  const statMap = new Map<string, any>();
  for (const s of stats!.results as any[]) statMap.set(`${s.provider_id}|${s.model}`, s);
  const stateMap = new Map<string, any>();
  for (const s of allState!.results as any[]) stateMap.set(`${s.provider_id}|${s.model}`, s);
  const now = Date.now();

  // Health rows: every model named in a route, plus any model with stats today.
  const models = new Map<string, Candidate & { tasks: string[] }>();
  for (const t of TASKS) for (const c of cfg.routes[t]) {
    const k = `${c.provider}|${c.model}`;
    const e = models.get(k) ?? { ...c, tasks: [] };
    e.tasks.push(t);
    models.set(k, e);
  }
  for (const k of statMap.keys()) if (!models.has(k)) { const i = k.indexOf("|"); models.set(k, { provider: k.slice(0, i), model: k.slice(i + 1), tasks: [] }); }
  const health = [...models.entries()].map(([k, c]) => {
    const st = stateMap.get(k), sp = stateMap.get(`${c.provider}|*`), s = statMap.get(k);
    const until = Math.max(st?.until ?? 0, sp?.until ?? 0);
    return {
      provider: c.provider, model: c.model, tasks: c.tasks,
      last_ok_at: st?.ok_at ?? 0, last_fail_at: st?.fail_at ?? 0,
      reason: (until > now ? (sp?.until > (st?.until ?? 0) ? `provider: ${sp.reason}` : st?.reason) : st?.state === "failed" ? `last: ${st.reason}` : "") || "",
      cooling_until: until > now ? until : 0,
      ok_today: s?.ok ?? 0, fail_today: s?.fail ?? 0, avg_ms: s?.ok ? Math.round(s.ms_total / s.ok) : 0,
    };
  });

  const providers = [...cfg.providers.values()].map(p => ({
    ...p,
    keys: cfg.keys.filter(k => k.provider_id === p.id).map(k => ({
      id: k.id, label: k.label, masked: masked(k.last4), enabled: k.enabled,
      cooling_until: k.cooling_until > now ? k.cooling_until : 0, last_error: k.last_error, last_ok_at: k.last_ok_at, last_fail_at: k.last_fail_at,
    })),
  }));
  const warnings: string[] = [];
  if (!env.KEY_ENCRYPTION_SECRET || env.KEY_ENCRYPTION_SECRET.length < MIN_SECRET_LEN) warnings.push(`KEY_ENCRYPTION_SECRET is missing or shorter than ${MIN_SECRET_LEN} characters: keys can't be saved and chat is off.`);
  if (!env.AI) warnings.push("No AI binding: the Workers AI fallback is off.");
  if (!env.AUTH_KV && !env.AUTH && !env.BLAZE_AUTH_URL) warnings.push("No way to check Blaze accounts (AUTH_KV / AUTH): every app request will fail.");
  if (!adminEmails(env).length) warnings.push("ADMIN_EMAILS is empty: only the admin token can sign in.");
  void marks;
  return json({
    version: VERSION, now, day, warnings,
    providers, routes: cfg.routes, tasks: TASKS, task_info: TASK_INFO,
    discovery: disc.results,
    plans: cfg.plans, default_plan: DEFAULT_PLAN,
    assignments: [...cfg.assignments.entries()].map(([email, plan]) => ({ email, plan })),
    health, usage_today: (usage!.results as any[])[0] ?? { users: 0, requests: 0, tokens: 0 },
  });
}

async function upsertProvider(req: Request, env: Env, idFromPath?: string): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const id = (idFromPath || str(b.id, 32)).toLowerCase();
  if (!ID_RE.test(id)) throw new HttpError(400, "bad_id", "Provider id: lower-case letters, digits, - or _");
  const type = str(b.type, 20) || "openai";
  if (type !== "openai" && type !== "workers-ai") throw new HttpError(400, "bad_type", "Type is 'openai' (OpenAI-compatible) or 'workers-ai'");
  const existing = await env.DB.prepare("SELECT * FROM providers WHERE id = ?1").bind(id).first<any>();
  const name = str(b.name, 80) || existing?.name || id;
  const base = checkBaseUrl(str(b.base_url, 300) || existing?.base_url || "", type);
  const enabled = b.enabled === undefined ? (existing ? existing.enabled : 1) : b.enabled ? 1 : 0;
  const notes = b.notes === undefined ? existing?.notes ?? "" : str(b.notes, 500);
  await env.DB.prepare(
    "INSERT INTO providers (id, name, base_url, type, enabled, notes, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
    "ON CONFLICT (id) DO UPDATE SET name = ?2, base_url = ?3, type = ?4, enabled = ?5, notes = ?6, updated_at = ?7",
  ).bind(id, name, base, type, enabled, notes, Date.now()).run();
  invalidateConfig();
  return json({ ok: true, id });
}

async function deleteProvider(env: Env, id: string): Promise<Response> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM provider_keys WHERE provider_id = ?1").bind(id),
    env.DB.prepare("DELETE FROM providers WHERE id = ?1").bind(id),
    env.DB.prepare("DELETE FROM model_state WHERE provider_id = ?1").bind(id),
  ]);
  invalidateConfig();
  return json({ ok: true });
}

const DUP_WINDOW_MS = 60_000;

async function addKey(req: Request, env: Env, provider: string): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const key = str(b.key, 2000);
  if (key.length < 8 || /\s/.test(key)) throw new HttpError(400, "bad_key", "Paste the whole key (no spaces)");
  const p = await env.DB.prepare("SELECT id FROM providers WHERE id = ?1").bind(provider).first();
  if (!p) throw new HttpError(404, "no_provider", "No such provider");
  // Same key twice (a double click, a second try): keep the first one. Ciphertexts differ every
  // time (fresh nonce), so compare the decrypted values; an undecryptable row with the same last 4
  // added under a minute ago also counts as the same key.
  const now = Date.now();
  const existing = await env.DB.prepare("SELECT id, enc, last4, created_at FROM provider_keys WHERE provider_id = ?1 ORDER BY id").bind(provider).all<any>();
  for (const k of existing.results) {
    let same = false;
    try { same = (await decryptKey(env.KEY_ENCRYPTION_SECRET, provider, k.enc)) === key; }
    catch { same = k.last4 === last4(key) && now - Number(k.created_at) < DUP_WINDOW_MS; }
    if (same) return json({ ok: true, id: k.id, masked: masked(k.last4), duplicate: true });
  }
  const enc = await encryptKey(env.KEY_ENCRYPTION_SECRET, provider, key).catch(e => { throw new HttpError(503, "no_secret", String((e as Error).message)); });
  const r = await env.DB.prepare("INSERT INTO provider_keys (provider_id, label, enc, last4, enabled, created_at) VALUES (?1, ?2, ?3, ?4, 1, ?5) RETURNING id")
    .bind(provider, str(b.label, 60), enc, last4(key), now).first<{ id: number }>();
  invalidateConfig();
  // A new key: find the provider's models right away (failures are recorded, never fatal here).
  let discovery: unknown = null;
  try {
    const cfg = await loadConfig(env.DB);
    const prow = cfg.providers.get(provider);
    if (prow && prow.type !== "workers-ai") discovery = await discoverProvider(env, prow, cfg.keys);
  } catch { /* the daily run will retry */ }
  return json({ ok: true, id: r?.id, masked: masked(last4(key)), discovery });
}

async function discoverOne(env: Env, id: string): Promise<Response> {
  invalidateConfig();
  const cfg = await loadConfig(env.DB);
  const p = cfg.providers.get(id);
  if (!p) throw new HttpError(404, "no_provider", "No such provider");
  return json(await discoverProvider(env, p, cfg.keys));
}

async function discoverEvery(env: Env): Promise<Response> {
  invalidateConfig();
  const cfg = await loadConfig(env.DB);
  return json({ results: await discoverAll(env, [...cfg.providers.values()], cfg.keys) });
}

async function listModels(env: Env): Promise<Response> {
  const [models, disc] = await Promise.all([
    loadModels(env.DB),
    env.DB.prepare("SELECT provider_id, ran_at, ok, count, source, error FROM discovery").all<any>(),
  ]);
  return json({ models: models.filter(m => m.free), discovery: disc.results, task_info: TASK_INFO });
}

/** POST /admin/api/routes/suggest {apply?: boolean}: best free lineup per task from discovered models. */
async function suggestRoutes(req: Request, env: Env): Promise<Response> {
  const b = await readJSON(req, 16_384).catch(() => ({} as Record<string, unknown>));
  invalidateConfig();
  const cfg = await loadConfig(env.DB);
  const models = await loadModels(env.DB);
  const usable = (id: string) => {
    const p = cfg.providers.get(id);
    if (!p || !p.enabled) return false;
    return p.type === "workers-ai" ? !!env.AI : cfg.keys.some(k => k.provider_id === id && k.enabled);
  };
  const routes = suggestLineup(models, usable);
  const empty = TASKS.filter(t => !routes[t].length);
  if (b.apply) {
    const t = Date.now();
    const stmts = TASKS.filter(task => routes[task].length).map(task => env.DB.prepare("INSERT INTO task_routes (task, models, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT (task) DO UPDATE SET models = ?2, updated_at = ?3")
      .bind(task, JSON.stringify(routes[task]), t));
    if (stmts.length) await env.DB.batch(stmts);
    invalidateConfig();
  }
  return json({ ok: true, routes, empty, applied: !!b.apply, models: models.length });
}

async function editKey(req: Request, env: Env, id: number): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const sets: string[] = [], vals: unknown[] = [];
  if (b.enabled !== undefined) { sets.push(`enabled = ?${vals.length + 2}`); vals.push(b.enabled ? 1 : 0); }
  if (b.label !== undefined) { sets.push(`label = ?${vals.length + 2}`); vals.push(str(b.label, 60)); }
  if (b.clear_cooldown) { sets.push("cooling_until = 0", "last_error = ''"); }
  if (!sets.length) throw new HttpError(400, "nothing", "Nothing to change");
  await env.DB.prepare(`UPDATE provider_keys SET ${sets.join(", ")} WHERE id = ?1`).bind(id, ...vals).run();
  invalidateConfig();
  return json({ ok: true });
}

async function deleteKey(env: Env, id: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM provider_keys WHERE id = ?1").bind(id).run();
  invalidateConfig();
  return json({ ok: true });
}

async function testKeyRoute(env: Env, id: number): Promise<Response> {
  const k = await env.DB.prepare("SELECT k.id, k.provider_id, k.enc, p.base_url, p.type FROM provider_keys k JOIN providers p ON p.id = k.provider_id WHERE k.id = ?1").bind(id).first<any>();
  if (!k) throw new HttpError(404, "no_key", "No such key");
  let plain: string;
  try { plain = await decryptKey(env.KEY_ENCRYPTION_SECRET, k.provider_id, k.enc); }
  catch { return json({ ok: false, status: 0, ms: 0, error: "Can't decrypt this key (KEY_ENCRYPTION_SECRET changed?). Delete it and add it again." }); }
  const r = await testKey(k.base_url, k.provider_id, plain);
  const now = Date.now();
  await env.DB.prepare(r.ok ? "UPDATE provider_keys SET last_ok_at = ?2, cooling_until = 0, last_error = '' WHERE id = ?1" : "UPDATE provider_keys SET last_fail_at = ?2, last_error = ?3 WHERE id = ?1")
    .bind(id, now, ...(r.ok ? [] : [`test ${r.status}`])).run();
  invalidateConfig();
  return json(r);
}

/** POST /admin/api/test-model {provider, model}: a 1-token completion through the real path. */
async function testModel(req: Request, env: Env): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const c: Candidate = { provider: str(b.provider, 32), model: str(b.model, 200) };
  invalidateConfig();
  const cfg = await loadConfig(env.DB);
  const p = cfg.providers.get(c.provider);
  if (!p) throw new HttpError(404, "no_provider", "No such provider");
  const body = { messages: [{ role: "user", content: "Reply with: OK" }], max_tokens: 5, stream: false };
  const t0 = Date.now();
  let up;
  if (p.type === "workers-ai") up = await callWorkersAI(env.AI, c.model, body, 30_000);
  else {
    const k = cfg.keys.find(x => x.provider_id === c.provider && x.enabled);
    if (!k) return json({ ok: false, status: 0, ms: 0, error: "No enabled key for this provider" });
    const plain = await decryptKey(env.KEY_ENCRYPTION_SECRET, k.provider_id, k.enc).catch(() => "");
    if (!plain) return json({ ok: false, status: 0, ms: 0, error: "Can't decrypt the key" });
    up = await callOpenAI(c.provider, p.base_url, plain, upstreamBody(body, c.model), 30_000);
  }
  const ms = Date.now() - t0;
  const marks = new Map<string, Mark>();
  const now = Date.now();
  if (up.status >= 200 && up.status < 300) {
    await up.body?.cancel().catch(() => undefined);
    const ok = markOk(marks, c, now);
    await saveMarkStmt(env.DB, ok.key, ok.mark).run();
    return json({ ok: true, status: up.status, ms });
  }
  const o = classify(c.provider, up.status, up.errorText);
  const ch = applyOutcome(marks, c, o, now);
  if (ch) await saveMarkStmt(env.DB, ch.key, ch.mark).run();
  return json({ ok: false, status: up.status, ms, kind: o.kind, error: up.errorText.slice(0, 300) });
}

async function clearCooldown(req: Request, env: Env): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const provider = str(b.provider, 32), model = str(b.model, 200);
  if (!provider) throw new HttpError(400, "missing", "provider");
  if (model) await env.DB.prepare("UPDATE model_state SET until = 0, state = 'ok', reason = '' WHERE provider_id = ?1 AND model IN (?2, '*')").bind(provider, model).run();
  else await env.DB.prepare("UPDATE model_state SET until = 0, state = 'ok', reason = '' WHERE provider_id = ?1").bind(provider).run();
  return json({ ok: true });
}

async function putRoute(req: Request, env: Env, taskName: string): Promise<Response> {
  const task = normalizeTask(taskName);
  if (!task || task !== taskName) throw new HttpError(404, "no_task", "Unknown task");
  const b = await readJSON(req, 65_536);
  if (!Array.isArray(b.models)) throw new HttpError(400, "bad_models", "models: [{provider, model}, …]");
  const models: Candidate[] = [];
  for (const m of b.models as any[]) {
    const provider = str(m?.provider, 32), model = str(m?.model, 200);
    if (!ID_RE.test(provider) || !model) throw new HttpError(400, "bad_models", `Bad entry: ${JSON.stringify(m).slice(0, 80)}`);
    models.push({ provider, model });
  }
  if (models.length > 40) throw new HttpError(400, "too_many", "At most 40 models per task");
  const cfg = await loadConfig(env.DB);
  for (const c of models) if (!isRoutable(c, cfg.paid)) {
    throw new HttpError(400, "paid_model", c.provider === "openrouter" ? `OpenRouter: only :free models (${c.model})` : `${c.provider} ${c.model} is paid-only`);
  }
  await env.DB.prepare("INSERT INTO task_routes (task, models, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT (task) DO UPDATE SET models = ?2, updated_at = ?3")
    .bind(task, JSON.stringify(models), Date.now()).run();
  invalidateConfig();
  return json({ ok: true, models });
}

async function resetRoutes(env: Env): Promise<Response> {
  const t = Date.now();
  await env.DB.batch(TASKS.map(task => env.DB.prepare("INSERT INTO task_routes (task, models, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT (task) DO UPDATE SET models = ?2, updated_at = ?3")
    .bind(task, JSON.stringify(DEFAULT_ROUTES[task]), t)));
  invalidateConfig();
  return json({ ok: true });
}

async function putPlan(req: Request, env: Env, id: string): Promise<Response> {
  if (!ID_RE.test(id)) throw new HttpError(400, "bad_id", "Plan id");
  const b = await readJSON(req, 16_384);
  const reqs = intIn(b.daily_requests, 0, 10_000_000);
  const toks = intIn(b.daily_tokens ?? 0, 0, 2_000_000_000);
  const price = Number(b.price_usd ?? 0);
  if (!Number.isFinite(price) || price < 0 || price > 100_000) throw new HttpError(400, "bad_value", "price_usd");
  await env.DB.prepare(
    "INSERT INTO plans (id, price_usd, daily_requests, daily_tokens, sort, updated_at) VALUES (?1, ?2, ?3, ?4, 99, ?5) " +
    "ON CONFLICT (id) DO UPDATE SET price_usd = ?2, daily_requests = ?3, daily_tokens = ?4, updated_at = ?5",
  ).bind(id, price, reqs, toks, Date.now()).run();
  invalidateConfig();
  return json({ ok: true });
}

async function putAssignment(req: Request, env: Env): Promise<Response> {
  const b = await readJSON(req, 16_384);
  const email = str(b.email, 254).toLowerCase();
  const plan = str(b.plan, 32);
  if (!EMAIL_RE.test(email)) throw new HttpError(400, "bad_email", "Email");
  if (!plan || plan === DEFAULT_PLAN) {
    await env.DB.prepare("DELETE FROM plan_assignments WHERE email = ?1").bind(email).run();
  } else {
    const p = await env.DB.prepare("SELECT id FROM plans WHERE id = ?1").bind(plan).first();
    if (!p) throw new HttpError(400, "no_plan", "No such plan");
    await env.DB.prepare("INSERT INTO plan_assignments (email, plan, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT (email) DO UPDATE SET plan = ?2, updated_at = ?3")
      .bind(email, plan, Date.now()).run();
  }
  invalidateConfig();
  return json({ ok: true });
}

export async function handleAdminApi(req: Request, env: Env, path: string): Promise<Response> {
  const m = req.method;
  if (path === "/admin/api/login" && m === "POST") return login(req, env);
  const admin = await requireAdmin(env, req);
  await ensureSeeded(env.DB);
  if (path === "/admin/api/me" && m === "GET") return json({ admin });
  if (path === "/admin/api/logout" && m === "POST") return logout(req, env);
  if (path === "/admin/api/state" && m === "GET") return state(env);
  if (path === "/admin/api/providers" && m === "POST") return upsertProvider(req, env);
  if (path === "/admin/api/seed" && m === "POST") { await seed(env.DB); invalidateConfig(); return json({ ok: true }); }
  if (path === "/admin/api/test-model" && m === "POST") return testModel(req, env);
  if (path === "/admin/api/cooldowns/clear" && m === "POST") return clearCooldown(req, env);
  if (path === "/admin/api/routes/reset" && m === "POST") return resetRoutes(env);
  if (path === "/admin/api/routes/suggest" && m === "POST") return suggestRoutes(req, env);
  if (path === "/admin/api/models" && m === "GET") return listModels(env);
  if (path === "/admin/api/discover" && m === "POST") return discoverEvery(env);
  if (path === "/admin/api/assignments" && m === "PUT") return putAssignment(req, env);
  let x: RegExpExecArray | null;
  if ((x = /^\/admin\/api\/providers\/([a-z0-9_-]{1,32})$/.exec(path))) {
    if (m === "PUT") return upsertProvider(req, env, x[1]);
    if (m === "DELETE") {
      if (x[1] === WORKERS_AI) throw new HttpError(400, "builtin", "Disable Workers AI instead of deleting it");
      return deleteProvider(env, x[1]!);
    }
  }
  if ((x = /^\/admin\/api\/providers\/([a-z0-9_-]{1,32})\/keys$/.exec(path)) && m === "POST") return addKey(req, env, x[1]!);
  if ((x = /^\/admin\/api\/providers\/([a-z0-9_-]{1,32})\/discover$/.exec(path)) && m === "POST") return discoverOne(env, x[1]!);
  if ((x = /^\/admin\/api\/keys\/(\d+)$/.exec(path))) {
    if (m === "PATCH") return editKey(req, env, Number(x[1]));
    if (m === "DELETE") return deleteKey(env, Number(x[1]));
  }
  if ((x = /^\/admin\/api\/keys\/(\d+)\/test$/.exec(path)) && m === "POST") return testKeyRoute(env, Number(x[1]));
  if ((x = /^\/admin\/api\/routes\/([a-z]+)$/.exec(path)) && m === "PUT") return putRoute(req, env, x[1]!);
  if ((x = /^\/admin\/api\/plans\/([a-z0-9_-]{1,32})$/.exec(path)) && m === "PUT") return putPlan(req, env, x[1]!);
  throw new HttpError(404, "not_found", "No such admin route");
}
