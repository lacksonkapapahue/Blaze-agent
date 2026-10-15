// Model discovery: asks each provider's own model-list API what it serves, keeps the chat models,
// tags what each can do (chat, vision, code, fast, long, tools), names them in plain words, and
// caches the result in D1 `models`. Also builds the "best free lineup" per task from that cache.
// Free only: OpenRouter keeps just its `:free` models, Nous just the models its live list prices at 0;
// anything flagged paid-only is never offered in /admin and never routed to (see isRoutable).
// Nothing here touches message content.

import { decryptKey } from "./crypto";
import { invalidateConfig, type ProviderRow, type KeyRow } from "./db";
import { DEFAULT_ROUTES, FAST_MS, FREE_LIST_ONLY, TEST_FIRST, WORKERS_AI, type Candidate } from "./defaults";
import type { Env } from "./env";
import { modelsUrl } from "./providers";
import { TASKS, type Task } from "./router";

export const CAPS = ["chat", "vision", "code", "fast", "long", "tools"] as const;
export type Cap = (typeof CAPS)[number];

export interface ModelInfo {
  provider: string;
  id: string;
  /** Friendly name, e.g. "Gemini Flash (fast, sees images)". */
  name: string;
  caps: Cap[];
  /** Context window in tokens, 0 = unknown. */
  context: number;
  free: boolean;
}

export interface DiscoveryResult {
  provider: string;
  ok: boolean;
  count: number;
  source: "api" | "binding" | "curated" | "";
  error?: string;
}

/** Plain-language description of each task, shown in the route editor. */
export const TASK_INFO: Record<Task, { title: string; text: string; needs?: Cap }> = {
  chat: { title: "Chat", text: "Everyday conversation: questions, writing, explanations. Pick the smartest models first." },
  quick: { title: "Quick", text: "Short, fast answers (titles, one-liners, quick replies). Speed matters more than depth.", needs: "fast" },
  code: { title: "Code", text: "Writing, fixing and explaining code. Coding-tuned models first." },
  vision: { title: "Vision", text: "The conversation contains pictures. Only models that can see images will work here.", needs: "vision" },
  long: { title: "Long", text: "Very long inputs (big documents, long chats). Models with a large memory (context window).", needs: "long" },
  agent: { title: "Agent", text: "The app lets the model use tools (search, actions). Models must support tool / function calling.", needs: "tools" },
};

export const LONG_CONTEXT = 120_000;

// ---------------- classification ----------------

/** Ids that aren't chat models at all (embeddings, speech, images, moderation, …). */
const NOT_CHAT = /(embed|whisper|tts|speech|audio|transcri|moderation|guard|rerank|imagen|veo|image-gen|image-preview|-image$|-image-|flux|stable-diffusion|dreamshaper|lucid|aqa|lyria|realtime|-live|native-audio|ocr|playai|orpheus|computer-use|robotics|nano-banana|antigravity|bge-|m2m100|resnet|detr|melotts|nova-3|aura-|smart-turn|translat|-classif|distilbert|bart-large|sdxl|phoenix|leonardo)/;

const VISION = /(vision|-vl\b|-vl-|vl-|pixtral|llava|scout|maverick|gemma-3-(?:4|12|27)b|gemma3|gemini|mistral-small-(?:latest|25|3)|mistral-medium|magistral-medium|ministral-(?:3|8|14)b-25|gpt-4o|gpt-4\.1|qwen2\.5-vl|qwen3-vl|kimi-vl|glm-4\.\dv|uform|internvl|nemotron-nano-12b-v2-vl)/;
const CODE = /(coder|codestral|devstral|code|gpt-oss|kimi-k2|qwen3-235b|qwen3-480b|glm-4\.[56]|deepseek-(?:v3|r1|chat)|sqlcoder)/;
const FAST = /(?:^|[-_/:.])(?:0\.5b|1b|1\.5b|2b|3b|4b|7b|8b|9b|mini|nano|lite|flash|instant|tiny|small|ministral|haiku|fast|turbo|e2b|e4b)(?:$|[-_/:.])/;
const PAID_GEMINI = /(gemini-3[\w.-]*-pro|-pro-preview|deep-research|ultra)/;

function lc(s: string): string {
  return s.toLowerCase();
}

export function isChatModel(id: string): boolean {
  return !NOT_CHAT.test(lc(id));
}

/** Capabilities from the provider's metadata where it gives them, otherwise from the id. */
export function inferCaps(provider: string, id: string, meta: { vision?: boolean; tools?: boolean; context?: number } = {}): Cap[] {
  const l = lc(id);
  const caps = new Set<Cap>(["chat"]);
  const vision = meta.vision ?? (VISION.test(l) && !(provider === "gemini" && l.includes("gemma-3-1b")));
  if (vision) caps.add("vision");
  if (CODE.test(l)) caps.add("code");
  if (FAST.test(l)) caps.add("fast");
  const ctx = meta.context ?? 0;
  if (ctx >= LONG_CONTEXT || (!ctx && (provider === "gemini" && l.startsWith("gemini")))) caps.add("long");
  const tools = meta.tools ?? (
    provider === "gemini" ? l.startsWith("gemini") :
    provider === "groq" || provider === "cerebras" ? /(llama-3\.[13]|llama-4|gpt-oss|kimi|qwen|compound|deepseek|glm)/.test(l) :
    /(gpt-oss|llama-3\.[13]-|llama-4|qwen|mistral|hermes|kimi|glm)/.test(l)
  );
  if (tools) caps.add("tools");
  return CAPS.filter(c => caps.has(c));
}

const WORD: Record<string, string> = {
  gpt: "GPT", oss: "OSS", llama: "Llama", qwen: "Qwen", qwq: "QwQ", gemma: "Gemma", gemini: "Gemini", mistral: "Mistral",
  deepseek: "DeepSeek", kimi: "Kimi", glm: "GLM", phi: "Phi", vl: "VL", ai: "AI", it: "", instruct: "", chat: "", hf: "",
  awq: "", fp8: "", int8: "", fp16: "", "fp8-fast": "", latest: "Latest", versatile: "", nemotron: "Nemotron", granite: "Granite",
  codestral: "Codestral", devstral: "Devstral", magistral: "Magistral", ministral: "Ministral", pixtral: "Pixtral",
  sea: "SEA", lion: "Lion", r1: "R1", v3: "V3", "v3.1": "V3.1", hermes: "Hermes", openchat: "OpenChat",
};

/** "@cf/meta/llama-3.3-70b-instruct-fp8-fast" → "Llama 3.3 70B". */
export function prettify(id: string): string {
  let s = id.replace(/^@(cf|hf)\//, "").replace(/:free$/, "").replace(/^models\//, "");
  if (s.includes("/")) s = s.slice(s.lastIndexOf("/") + 1);
  const words = s.split(/[-_ ]+/).filter(Boolean).map(w => {
    const k = lc(w);
    if (k in WORD) return WORD[k]!;
    if (/^\d+(\.\d+)?[bmk]$/.test(k)) return k.toUpperCase();
    if (/^a\d+(\.\d+)?b$/.test(k)) return k.toUpperCase();
    if (/^\d+e$/.test(k)) return k.toUpperCase();
    if (/^\d/.test(k)) return w;
    return w.charAt(0).toUpperCase() + w.slice(1);
  }).filter(Boolean);
  return words.join(" ").replace(/\s+/g, " ").trim() || id;
}

/** "Gemini Flash" + caps → "Gemini Flash (fast, sees images)". */
export function friendly(base: string, caps: Cap[]): string {
  const t: string[] = [];
  if (caps.includes("fast")) t.push("fast");
  if (caps.includes("vision")) t.push("sees images");
  if (caps.includes("code")) t.push("good at code");
  if (caps.includes("long")) t.push("long inputs");
  const b = base.replace(/\s*\((free|latest)\)\s*$/i, "").trim();
  return t.length ? `${b} (${t.slice(0, 3).join(", ")})` : b;
}

function cleanName(raw: unknown, id: string): string {
  let n = typeof raw === "string" ? raw.trim() : "";
  // No name, or a slug instead of a name (Mistral reports its canonical id, e.g. "mistral-medium-2508",
  // for every alias): name it from this model's own id so aliases stay distinguishable.
  if (!n || n === id || !n.includes(" ")) return prettify(id);
  n = n.replace(/\s*\(free\)\s*$/i, "");
  const colon = n.indexOf(": ");
  if (colon > 0 && colon < 30) n = n.slice(colon + 2); // OpenRouter "Vendor: Model"
  return n.slice(0, 80);
}

/** Two models of one provider with the same friendly name: name them from their ids instead,
 *  and if that still collides, add the id. Keeps the dropdowns unambiguous. */
export function uniqueNames(models: ModelInfo[]): ModelInfo[] {
  const count = (list: ModelInfo[]) => {
    const n = new Map<string, number>();
    for (const m of list) n.set(`${m.provider}|${m.name}`, (n.get(`${m.provider}|${m.name}`) ?? 0) + 1);
    return n;
  };
  let n = count(models);
  let out = models.map(m => (n.get(`${m.provider}|${m.name}`)! > 1 ? { ...m, name: friendly(prettify(m.id), m.caps) } : m));
  n = count(out);
  out = out.map(m => (n.get(`${m.provider}|${m.name}`)! > 1 ? { ...m, name: `${m.name} · ${m.id}` } : m));
  return out;
}

function mk(provider: string, id: string, rawName: unknown, meta: { vision?: boolean; tools?: boolean; context?: number }, free = true): ModelInfo {
  const caps = inferCaps(provider, id, meta);
  return { provider, id, name: friendly(cleanName(rawName, id), caps), caps, context: meta.context ?? 0, free };
}

// ---------------- provider list parsers (pure) ----------------

/** OpenRouter-style `pricing` ({prompt: "0", completion: "0"}): free only when both are exactly 0. */
export function pricedFree(pricing: unknown): boolean {
  if (!pricing || typeof pricing !== "object") return false;
  const p = pricing as { prompt?: unknown; completion?: unknown };
  const n = (v: unknown) => (v === undefined || v === null || v === "" ? NaN : Number(v));
  return n(p.prompt) === 0 && n(p.completion ?? 0) === 0;
}

/** OpenAI-style {data:[{id, …}]} from Groq, Cerebras, Mistral, OpenRouter, Nous, NVIDIA, GitHub Models. */
export function parseOpenAIList(provider: string, body: any): ModelInfo[] {
  const list: any[] = Array.isArray(body?.data) ? body.data : Array.isArray(body) ? body : [];
  const out: ModelInfo[] = [];
  const seen = new Set<string>();
  for (const m of list) {
    const id = typeof m?.id === "string" ? m.id : "";
    if (!id || seen.has(id)) continue;
    if (m.active === false || m.archived === true) continue;
    if (!isChatModel(id)) continue;
    if (provider === "openrouter" && !id.endsWith(":free")) continue; // free-only rule
    if (FREE_LIST_ONLY.has(provider) && !pricedFree(m.pricing)) continue; // Nous: price 0 only (the list rotates)
    const c = m.capabilities;
    if (provider === "mistral" && c && typeof c === "object" && c.completion_chat === false) continue;
    const arch = m.architecture?.input_modalities;
    const out_mod = m.architecture?.output_modalities;
    if (Array.isArray(out_mod) && !out_mod.includes("text")) continue;
    const meta: { vision?: boolean; tools?: boolean; context?: number } = {};
    if (Array.isArray(arch)) meta.vision = arch.includes("image");
    if (c && typeof c === "object" && typeof c.vision === "boolean") meta.vision = c.vision;
    if (c && typeof c === "object" && typeof c.function_calling === "boolean") meta.tools = c.function_calling;
    if (Array.isArray(m.supported_parameters)) meta.tools = m.supported_parameters.includes("tools");
    const ctx = Number(m.context_length ?? m.context_window ?? m.max_context_length ?? m.max_model_len ?? 0);
    if (ctx > 0) meta.context = ctx;
    let free = true;
    if (provider === "openrouter" && m.pricing && (Number(m.pricing.prompt) > 0 || Number(m.pricing.completion) > 0)) free = false;
    seen.add(id);
    out.push(mk(provider, id, m.name, meta, free));
  }
  return uniqueNames(out);
}

/** Gemini native v1beta/models: {models:[{name:"models/…", displayName, inputTokenLimit, supportedGenerationMethods}]} */
export function parseGemini(body: any): ModelInfo[] {
  const list: any[] = Array.isArray(body?.models) ? body.models : [];
  const out: ModelInfo[] = [];
  for (const m of list) {
    const id = String(m?.name || "").replace(/^models\//, "");
    if (!id) continue;
    const methods: string[] = Array.isArray(m.supportedGenerationMethods) ? m.supportedGenerationMethods : [];
    if (methods.length && !methods.includes("generateContent")) continue;
    if (!isChatModel(id)) continue;
    const l = lc(id);
    const gemma = l.startsWith("gemma");
    const meta = { context: Number(m.inputTokenLimit) || 0, vision: gemma ? VISION.test(l) : true, tools: !gemma };
    out.push(mk("gemini", id, m.displayName, meta, !PAID_GEMINI.test(l)));
  }
  return uniqueNames(out);
}

export interface CfModel { name?: string; id?: string; task?: { name?: string }; properties?: { property_id: string; value: unknown }[]; description?: string }

/** The Workers AI catalogue (AI binding `models()`): text-generation models only. */
export function parseWorkersAI(list: CfModel[]): ModelInfo[] {
  const out: ModelInfo[] = [];
  const seen = new Set<string>();
  for (const m of list) {
    const id = String(m?.name || "");
    if (!id.startsWith("@") || seen.has(id)) continue;
    const task = lc(String(m.task?.name || ""));
    if (task && task !== "text generation" && task !== "image-text-to-text") continue;
    if (!isChatModel(id)) continue;
    const props = new Map((m.properties || []).map(p => [lc(String(p.property_id)), lc(String(p.value))]));
    if (props.get("planned_deprecation_date") && Date.parse(props.get("planned_deprecation_date")!) < Date.now()) continue;
    const ctx = Number(props.get("context_window") || props.get("max_input_tokens") || 0);
    const meta: { vision?: boolean; tools?: boolean; context?: number } = {};
    if (ctx > 0) meta.context = ctx;
    if (props.has("function_calling")) meta.tools = props.get("function_calling") === "true";
    if (props.has("vision")) meta.vision = props.get("vision") === "true";
    seen.add(id);
    out.push(mk(WORKERS_AI, id, undefined, meta));
  }
  return uniqueNames(out);
}

/** Used when the AI binding can't list models (local dev, older runtimes). Ids from Cloudflare's catalogue. */
export const CURATED_WORKERS_AI: CfModel[] = [
  ["@cf/meta/llama-3.3-70b-instruct-fp8-fast", 24000, true],
  ["@cf/meta/llama-4-scout-17b-16e-instruct", 131000, true, true],
  ["@cf/meta/llama-3.1-8b-instruct-fast", 128000, false],
  ["@cf/meta/llama-3.1-8b-instruct", 7968, false],
  ["@cf/meta/llama-3.2-11b-vision-instruct", 128000, false, true],
  ["@cf/meta/llama-3.2-3b-instruct", 128000, false],
  ["@cf/meta/llama-3.2-1b-instruct", 60000, false],
  ["@cf/qwen/qwen2.5-coder-32b-instruct", 32768, false],
  ["@cf/qwen/qwq-32b", 24000, false],
  ["@cf/qwen/qwen3-30b-a3b-fp8", 32768, true],
  ["@cf/openai/gpt-oss-120b", 128000, true],
  ["@cf/openai/gpt-oss-20b", 128000, true],
  ["@cf/mistralai/mistral-small-3.1-24b-instruct", 128000, true, true],
  ["@cf/google/gemma-3-12b-it", 80000, false, true],
  ["@cf/deepseek-ai/deepseek-r1-distill-qwen-32b", 80000, false],
  ["@cf/ibm-granite/granite-4.0-h-micro", 131000, true],
].map(([name, ctx, tools, vision]) => ({
  name: name as string, task: { name: "Text Generation" },
  properties: [{ property_id: "context_window", value: String(ctx) }, { property_id: "function_calling", value: String(!!tools) }, ...(vision ? [{ property_id: "vision", value: "true" }] : [])],
}));

// ---------------- fetching ----------------

/** Providers whose model list is public (discovery works before a key is added). */
const KEYLESS_LIST = new Set(["openrouter", "nous"]);

async function getJSON(url: string, headers: Record<string, string>, fetcher: typeof fetch): Promise<{ ok: boolean; status: number; body: any; error: string }> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort("timeout"), 20_000);
  try {
    const res = await fetcher(url, { headers, signal: ctl.signal });
    clearTimeout(timer);
    if (!res.ok) return { ok: false, status: res.status, body: null, error: `HTTP ${res.status}: ${(await res.text().catch(() => "")).slice(0, 160)}` };
    return { ok: true, status: res.status, body: await res.json(), error: "" };
  } catch (e) {
    clearTimeout(timer);
    return { ok: false, status: 0, body: null, error: String((e as Error)?.message || e).slice(0, 160) };
  }
}

async function firstKey(env: Env, keys: KeyRow[], provider: string): Promise<string> {
  for (const k of keys) {
    if (k.provider_id !== provider || !k.enabled) continue;
    try { return await decryptKey(env.KEY_ENCRYPTION_SECRET, provider, k.enc); } catch { /* next */ }
  }
  return "";
}

export function hasKey(keys: KeyRow[], provider: string): boolean {
  return keys.some(k => k.provider_id === provider && k.enabled);
}

/** Asks one provider for its models. Doesn't write anything. */
export async function fetchModels(env: Env, p: ProviderRow, keys: KeyRow[], fetcher: typeof fetch = fetch): Promise<{ models: ModelInfo[]; source: DiscoveryResult["source"]; error?: string }> {
  if (p.type === "workers-ai") {
    const ai = env.AI as unknown as { models?: (o?: Record<string, unknown>) => Promise<CfModel[]> } | undefined;
    if (ai && typeof ai.models === "function") {
      try {
        const all: CfModel[] = [];
        for (let page = 1; page <= 5; page++) {
          const batch = await ai.models({ task: "Text Generation", per_page: 100, page });
          if (!Array.isArray(batch) || !batch.length) break;
          all.push(...batch);
          if (batch.length < 100) break;
        }
        const models = parseWorkersAI(all);
        if (models.length) return { models, source: "binding" };
      } catch { /* fall back to the curated list */ }
    }
    return { models: parseWorkersAI(CURATED_WORKERS_AI), source: "curated" };
  }
  const key = await firstKey(env, keys, p.id);
  if (!key && !KEYLESS_LIST.has(p.id)) return { models: [], source: "", error: "Add a key first" };
  const native = /generativelanguage\.googleapis\.com/.test(p.base_url);
  if (native) {
    const base = p.base_url.replace(/\/openai\/?$/, "").replace(/\/+$/, "");
    const r = await getJSON(`${base}/models?pageSize=1000`, { "x-goog-api-key": key }, fetcher);
    if (!r.ok) return { models: [], source: "", error: r.error };
    return { models: parseGemini(r.body), source: "api" };
  }
  const headers: Record<string, string> = {};
  if (key) headers.Authorization = `Bearer ${key}`;
  const r = await getJSON(modelsUrl(p.base_url), headers, fetcher);
  if (!r.ok) return { models: [], source: "", error: r.error };
  return { models: parseOpenAIList(p.id, r.body), source: "api" };
}

/** Replaces [provider]'s cached models (only when the fetch worked) and records the run. */
export async function saveDiscovery(db: D1Database, provider: string, models: ModelInfo[], source: string, error: string | undefined, now = Date.now()): Promise<void> {
  const stmts: D1PreparedStatement[] = [];
  const ok = !error;
  if (ok) {
    stmts.push(db.prepare("DELETE FROM models WHERE provider_id = ?1").bind(provider));
    for (const m of models.slice(0, 500)) {
      stmts.push(db.prepare("INSERT OR REPLACE INTO models (provider_id, model_id, name, caps, context, free, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)")
        .bind(provider, m.id.slice(0, 200), m.name.slice(0, 120), JSON.stringify(m.caps), Math.round(m.context) || 0, m.free ? 1 : 0, now));
    }
  }
  stmts.push(db.prepare(
    "INSERT INTO discovery (provider_id, ran_at, ok, count, source, error) VALUES (?1, ?2, ?3, ?4, ?5, ?6) " +
    "ON CONFLICT (provider_id) DO UPDATE SET ran_at = ?2, ok = ?3, count = CASE WHEN ?3 = 1 THEN ?4 ELSE count END, source = CASE WHEN ?3 = 1 THEN ?5 ELSE source END, error = ?6",
  ).bind(provider, now, ok ? 1 : 0, ok ? models.length : 0, ok ? source : "", (error || "").slice(0, 200)));
  await db.batch(stmts);
  invalidateConfig(); // the paid-only set lives in the config cache
}

export async function discoverProvider(env: Env, p: ProviderRow, keys: KeyRow[], fetcher: typeof fetch = fetch): Promise<DiscoveryResult> {
  const r = await fetchModels(env, p, keys, fetcher);
  if (r.error === "Add a key first") return { provider: p.id, ok: false, count: 0, source: "", error: r.error };
  await saveDiscovery(env.DB, p.id, r.models, r.source || "", r.error);
  return { provider: p.id, ok: !r.error, count: r.models.length, source: r.source, error: r.error };
}

const DAY_MS = 20 * 3600_000;   // refresh successful lists about daily
const RETRY_MS = 6 * 3600_000;  // retry failed ones a few times a day

/**
 * Discovery for every enabled provider that can list (key added, or keyless). With [onlyStale]
 * (the cron), skips providers whose last run is recent, so a frequent cron costs one D1 read.
 */
export async function discoverAll(env: Env, providers: ProviderRow[], keys: KeyRow[], opts: { onlyStale?: boolean; now?: number; fetcher?: typeof fetch } = {}): Promise<DiscoveryResult[]> {
  const now = opts.now ?? Date.now();
  const last = new Map<string, { ran_at: number; ok: number }>();
  if (opts.onlyStale) {
    const rows = await env.DB.prepare("SELECT provider_id, ran_at, ok FROM discovery").all<any>();
    for (const r of rows.results) last.set(r.provider_id, r);
  }
  const todo = providers.filter(p => {
    if (!p.enabled) return false;
    if (p.type !== "workers-ai" && !hasKey(keys, p.id) && !KEYLESS_LIST.has(p.id)) return false;
    if (p.type === "workers-ai" && !env.AI) return false;
    const l = last.get(p.id);
    if (opts.onlyStale && l && now - l.ran_at < (l.ok ? DAY_MS : RETRY_MS)) return false;
    return true;
  });
  const out: DiscoveryResult[] = [];
  for (const p of todo) out.push(await discoverProvider(env, p, keys, opts.fetcher).catch(e => ({ provider: p.id, ok: false, count: 0, source: "" as const, error: String((e as Error)?.message || e).slice(0, 160) })));
  return out;
}

export async function loadModels(db: D1Database): Promise<ModelInfo[]> {
  const rows = await db.prepare("SELECT provider_id, model_id, name, caps, context, free FROM models ORDER BY provider_id, name").all<any>();
  return rows.results.map(r => {
    let caps: Cap[] = [];
    try { caps = (JSON.parse(r.caps) as string[]).filter((c): c is Cap => (CAPS as readonly string[]).includes(c)); } catch { /* none */ }
    return { provider: r.provider_id, id: r.model_id, name: r.name, caps, context: Number(r.context) || 0, free: !!r.free };
  });
}

// ---------------- the free-only rule ----------------

/** What request-time routing knows beyond the paid set (Config.listed / Config.vetted). */
export interface RouteGate {
  /** "provider|model" on the latest discovered free list of a FREE_LIST_ONLY provider (Nous). */
  listed?: ReadonlySet<string>;
  /** "provider|model" whose latest /admin Test passed within FAST_MS. */
  vetted?: ReadonlySet<string>;
}

/** Why the gateway won't send traffic to this model, or null when it may. */
export function notRoutableReason(c: Candidate, paid: ReadonlySet<string>, gate: RouteGate = {}): string | null {
  const k = `${c.provider}|${c.model}`;
  if (c.provider === "openrouter" && !c.model.endsWith(":free")) return `OpenRouter: only :free models (${c.model})`;
  if (paid.has(k)) return `${c.provider} ${c.model} is paid-only`;
  if (FREE_LIST_ONLY.has(c.provider) && !gate.listed?.has(k)) return `${c.provider} ${c.model} isn't on ${c.provider}'s free list right now (press Find models to refresh it)`;
  if (TEST_FIRST.has(c.provider) && !gate.vetted?.has(k)) return `${c.provider} ${c.model} must pass Test and reply within ${FAST_MS / 1000} s before it can be routed`;
  return null;
}

/**
 * May the gateway send traffic to this model? OpenRouter: `:free` only; anything flagged paid: never;
 * Nous: only while on its free list and after passing Test within FAST_MS.
 */
export function isRoutable(c: Candidate, paid: ReadonlySet<string>, gate: RouteGate = {}): boolean {
  return notRoutableReason(c, paid, gate) === null;
}

// ---------------- "Suggest best free lineup" ----------------

const PROVIDER_BONUS: Record<string, number> = { gemini: 1.2, groq: 0.9, cerebras: 0.8, mistral: 0.6, nvidia: 0.3, nous: 0.2, openrouter: 0, github: -1, [WORKERS_AI]: -3 };

/** Rough quality on a log2(billions of parameters) scale, from the id. */
export function quality(id: string): number {
  const l = lc(id);
  if (/gemini[\w.-]*pro/.test(l)) return 8;
  if (/gemini[\w.-]*flash-lite/.test(l)) return 5.5;
  if (/gemini[\w.-]*flash/.test(l)) return 7.2;
  if (/kimi-k2|deepseek-(v3|r1|chat)|qwen3-(235|480)b|glm-4\.[56]/.test(l)) return 7.6;
  if (/maverick/.test(l)) return 6.6;
  if (/scout/.test(l)) return 6.2;
  if (/(mistral|magistral)-large/.test(l)) return 7;
  if (/(mistral|magistral)-medium/.test(l)) return 6.8;
  if (/devstral-medium/.test(l)) return 6.5;
  if (/codestral|devstral/.test(l)) return 5.8;
  if (/mistral-small/.test(l)) return 4.8;
  const sizes = [...l.matchAll(/(?:^|[-_/])(\d+(?:\.\d+)?)b(?:$|[-_/:.])/g)].map(m => Number(m[1]));
  if (sizes.length) return Math.log2(Math.max(1, Math.max(...sizes)));
  if (/ministral|mini|nano|lite|tiny/.test(l)) return 3.5;
  return 5;
}

export function scoreFor(task: Task, m: ModelInfo): number | null {
  const has = (c: Cap) => m.caps.includes(c);
  const need = TASK_INFO[task].needs;
  if (need && need !== "fast" && !has(need)) return null;
  const l = lc(m.id);
  let s = quality(m.id) + (PROVIDER_BONUS[m.provider] ?? 0);
  if (l.endsWith("-latest")) s += 0.3;
  if (/preview|-exp|experimental|beta/.test(l)) s -= 0.4;
  if (/distill|guard|safety/.test(l)) s -= 1;
  if (DEFAULT_ROUTES[task].some(c => c.provider === m.provider && c.model === m.id)) s += 1.5;
  if (task === "quick") s = (has("fast") ? 4 : -4) + (PROVIDER_BONUS[m.provider] ?? 0) * 1.5 - quality(m.id) * 0.3 + (DEFAULT_ROUTES.quick.some(c => c.provider === m.provider && c.model === m.id) ? 1.5 : 0);
  if (task === "code" && has("code")) s += 2.5;
  if (task === "long") s += Math.log2(Math.max(1, m.context || LONG_CONTEXT) / 1000) / 3;
  if (task === "agent" && has("code")) s += 0.5;
  return s;
}

/** Best free lineup for one task: up to [max] models, at most 2 per provider, Workers AI last. */
export function suggestTask(task: Task, models: ModelInfo[], usable: (provider: string) => boolean, max = 6): Candidate[] {
  const scored = models
    .filter(m => m.free && m.caps.includes("chat") && usable(m.provider))
    .map(m => ({ m, s: scoreFor(task, m) }))
    .filter((x): x is { m: ModelInfo; s: number } => x.s !== null)
    .sort((a, b) => b.s - a.s || a.m.id.localeCompare(b.m.id));
  const out: Candidate[] = [];
  const perProvider = new Map<string, number>();
  for (const { m } of scored) {
    if (m.provider === WORKERS_AI) continue;
    if (out.length >= max - 1) break;
    const n = perProvider.get(m.provider) ?? 0;
    if (n >= 2) continue;
    perProvider.set(m.provider, n + 1);
    out.push({ provider: m.provider, model: m.id });
  }
  const cf = scored.find(x => x.m.provider === WORKERS_AI);
  if (cf) out.push({ provider: WORKERS_AI, model: cf.m.id });
  return out;
}

export function suggestLineup(models: ModelInfo[], usable: (provider: string) => boolean): Record<Task, Candidate[]> {
  const out = {} as Record<Task, Candidate[]>;
  for (const t of TASKS) out[t] = suggestTask(t, models, usable);
  return out;
}
