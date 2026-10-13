// Provider adapters. Every OpenAI-compatible provider (Groq, OpenRouter, Cerebras, Mistral,
// GitHub Models, Gemini's /v1beta/openai, NVIDIA NIM) is one fetch to {base_url}/chat/completions.
// Workers AI runs through the AI binding and is translated to the OpenAI shape here.
// Nothing in this file logs or stores message content.

import type { AiBinding } from "./env";

export interface Upstream {
  status: number;
  headers: Headers;
  /** Set when status is 2xx: the body to hand the client (SSE when streaming, JSON otherwise). */
  body: ReadableStream<Uint8Array> | null;
  /** Error text (first 2 KB) for classification; never logged. */
  errorText: string;
}

/** Fields only the gateway understands; never sent upstream. */
const PRIVATE_FIELDS = ["blaze_task", "user"];

export function upstreamBody(body: Record<string, unknown>, model: string): Record<string, unknown> {
  const out: Record<string, unknown> = { ...body, model };
  for (const f of PRIVATE_FIELDS) delete out[f];
  for (const k of Object.keys(out)) if (k.startsWith("blaze_")) delete out[k];
  return out;
}

export function chatUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, "") + "/chat/completions";
}

export function modelsUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, "") + "/models";
}

function providerHeaders(provider: string, key: string): Record<string, string> {
  const h: Record<string, string> = { "Content-Type": "application/json", Authorization: `Bearer ${key}` };
  if (provider === "openrouter") {
    h["HTTP-Referer"] = "https://github.com/lacksonkapapahue/Blaze-agent";
    h["X-Title"] = "Blaze";
  }
  return h;
}

async function errorText(res: Response): Promise<string> {
  try {
    return (await res.text()).slice(0, 2048);
  } catch {
    return "";
  }
}

/**
 * One call to an OpenAI-compatible provider. Resolves once the response headers arrive (or the
 * call fails); a streaming body keeps flowing afterwards. [timeoutMs] bounds the wait for headers.
 */
export async function callOpenAI(provider: string, baseUrl: string, key: string, body: Record<string, unknown>, timeoutMs: number, fetcher: typeof fetch = fetch): Promise<Upstream> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort("timeout"), timeoutMs);
  try {
    const res = await fetcher(chatUrl(baseUrl), { method: "POST", headers: providerHeaders(provider, key), body: JSON.stringify(body), signal: ctl.signal });
    clearTimeout(timer);
    if (!res.ok) return { status: res.status, headers: res.headers, body: null, errorText: await errorText(res) };
    return { status: res.status, headers: res.headers, body: res.body, errorText: "" };
  } catch (e) {
    clearTimeout(timer);
    return { status: 0, headers: new Headers(), body: null, errorText: String((e as Error)?.message || e).slice(0, 300) };
  }
}

/** Cheap key check: GET {base}/models (no tokens spent). */
export async function testKey(baseUrl: string, provider: string, key: string, fetcher: typeof fetch = fetch): Promise<{ ok: boolean; status: number; ms: number; models?: number; error?: string }> {
  const t = Date.now();
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort("timeout"), 15_000);
  try {
    const res = await fetcher(modelsUrl(baseUrl), { headers: providerHeaders(provider, key), signal: ctl.signal });
    clearTimeout(timer);
    const ms = Date.now() - t;
    if (!res.ok) return { ok: false, status: res.status, ms, error: (await errorText(res)).slice(0, 200) };
    const b = await res.json<{ data?: unknown[] }>().catch(() => ({} as { data?: unknown[] }));
    return { ok: true, status: res.status, ms, models: Array.isArray(b.data) ? b.data.length : undefined };
  } catch (e) {
    clearTimeout(timer);
    return { ok: false, status: 0, ms: Date.now() - t, error: String((e as Error)?.message || e).slice(0, 200) };
  }
}

// ---------------- Workers AI ----------------

const enc = new TextEncoder();
const dec = new TextDecoder();

function chunk(id: string, created: number, model: string, delta: Record<string, unknown>, finish: string | null, usage?: unknown): string {
  const o: Record<string, unknown> = { id, object: "chat.completion.chunk", created, model, choices: [{ index: 0, delta, finish_reason: finish }] };
  if (usage) o.usage = usage;
  return `data: ${JSON.stringify(o)}\n\n`;
}

interface CfToolCall { name?: string; arguments?: unknown; id?: string; function?: { name?: string; arguments?: unknown } }

function openaiToolCalls(calls: CfToolCall[]) {
  return calls.map((tc, i) => {
    const name = tc.function?.name ?? tc.name ?? "";
    const args = tc.function?.arguments ?? tc.arguments ?? {};
    return { index: i, id: tc.id || `call_${i}_${Math.random().toString(36).slice(2, 10)}`, type: "function", function: { name, arguments: typeof args === "string" ? args : JSON.stringify(args) } };
  });
}

/** Workers AI non-streaming result → OpenAI chat.completion. */
export function fromWorkersAI(result: unknown, model: string, now = Date.now()): Record<string, unknown> {
  const r = (result ?? {}) as { response?: unknown; tool_calls?: CfToolCall[]; usage?: Record<string, number>; choices?: unknown };
  if (Array.isArray(r.choices)) return { ...(r as object), model } as Record<string, unknown>; // already OpenAI-shaped
  const calls = Array.isArray(r.tool_calls) && r.tool_calls.length ? openaiToolCalls(r.tool_calls) : null;
  const content = typeof r.response === "string" ? r.response : r.response == null ? "" : JSON.stringify(r.response);
  const message: Record<string, unknown> = { role: "assistant", content: calls && !content ? null : content };
  if (calls) message.tool_calls = calls.map(({ index: _i, ...c }) => c);
  return {
    id: "chatcmpl-cf" + now.toString(36),
    object: "chat.completion",
    created: Math.floor(now / 1000),
    model,
    choices: [{ index: 0, message, finish_reason: calls ? "tool_calls" : "stop" }],
    usage: r.usage ?? undefined,
  };
}

/** A finished completion replayed as an SSE stream (used for Workers AI tool turns). */
export function completionToSSE(c: Record<string, unknown>): ReadableStream<Uint8Array> {
  const id = String(c.id), created = Number(c.created), model = String(c.model);
  const choice = ((c.choices as any[]) || [])[0] || {};
  const msg = choice.message || {};
  let out = chunk(id, created, model, { role: "assistant", content: "" }, null);
  if (msg.content) out += chunk(id, created, model, { content: msg.content }, null);
  if (Array.isArray(msg.tool_calls) && msg.tool_calls.length) out += chunk(id, created, model, { tool_calls: msg.tool_calls.map((t: any, i: number) => ({ index: i, ...t })) }, null);
  out += chunk(id, created, model, {}, choice.finish_reason || "stop", c.usage);
  out += "data: [DONE]\n\n";
  return new ReadableStream({ start(ctl) { ctl.enqueue(enc.encode(out)); ctl.close(); } });
}

/** Workers AI SSE ({"response":"…"} events) → OpenAI chat.completion.chunk events. */
export function workersAIStreamToOpenAI(src: ReadableStream<Uint8Array>, model: string, now = Date.now()): ReadableStream<Uint8Array> {
  const id = "chatcmpl-cf" + now.toString(36);
  const created = Math.floor(now / 1000);
  let buf = "";
  let started = false;
  let finished = false;
  let usage: unknown;
  const emitLine = (line: string, ctl: TransformStreamDefaultController<Uint8Array>) => {
    if (!line.startsWith("data:")) return;
    const data = line.slice(5).trim();
    if (!data) return;
    if (data === "[DONE]") {
      if (!finished) ctl.enqueue(enc.encode(chunk(id, created, model, {}, "stop", usage) + "data: [DONE]\n\n"));
      finished = true;
      return;
    }
    let ev: any;
    try { ev = JSON.parse(data); } catch { return; }
    if (Array.isArray(ev.choices)) { // already OpenAI-shaped
      ctl.enqueue(enc.encode(`data: ${JSON.stringify({ ...ev, model })}\n\n`));
      return;
    }
    if (ev.usage) usage = ev.usage;
    const delta: Record<string, unknown> = {};
    if (!started) { delta.role = "assistant"; started = true; }
    if (typeof ev.response === "string" && ev.response) delta.content = ev.response;
    if (Array.isArray(ev.tool_calls) && ev.tool_calls.length) delta.tool_calls = openaiToolCalls(ev.tool_calls);
    if (Object.keys(delta).length) ctl.enqueue(enc.encode(chunk(id, created, model, delta, null)));
  };
  return src.pipeThrough(new TransformStream<Uint8Array, Uint8Array>({
    transform(bytes, ctl) {
      buf += dec.decode(bytes, { stream: true });
      let i: number;
      while ((i = buf.indexOf("\n")) >= 0) {
        emitLine(buf.slice(0, i).trim(), ctl);
        buf = buf.slice(i + 1);
      }
    },
    flush(ctl) {
      if (buf.trim()) emitLine(buf.trim(), ctl);
      if (!finished) ctl.enqueue(enc.encode(chunk(id, created, model, {}, "stop", usage) + "data: [DONE]\n\n"));
    },
  }));
}

/** Map an exception from the AI binding to an HTTP-ish status for classify(). */
export function workersAIErrorStatus(msg: string): number {
  const m = msg.toLowerCase();
  if (m.includes("3036") || m.includes("4006") || m.includes("daily free allocation") || m.includes("neurons")) return 429;
  if (m.includes("3040") || m.includes("capacity") || m.includes("rate limit") || m.includes("too many")) return 429;
  if (m.includes("5007") || m.includes("no such model") || m.includes("not found")) return 404;
  if (m.includes("context") || m.includes("too long") || m.includes("too large")) return 413;
  if (m.includes("timeout") || m.includes("timed out")) return 0;
  if (m.includes("invalid") || m.includes("input")) return 400;
  return 500;
}

export async function callWorkersAI(ai: AiBinding | undefined, model: string, body: Record<string, unknown>, timeoutMs: number): Promise<Upstream> {
  if (!ai) return { status: 503, headers: new Headers(), body: null, errorText: "AI binding missing" };
  const stream = body.stream === true;
  const tools = Array.isArray(body.tools) && body.tools.length > 0 ? body.tools : undefined;
  const inputs: Record<string, unknown> = {
    messages: body.messages,
    max_tokens: typeof body.max_tokens === "number" ? body.max_tokens : typeof body.max_completion_tokens === "number" ? body.max_completion_tokens : 2048,
  };
  for (const k of ["temperature", "top_p", "seed", "frequency_penalty", "presence_penalty", "response_format"]) if (body[k] !== undefined) inputs[k] = body[k];
  if (tools) inputs.tools = tools;
  // Tool turns run non-streaming (tool-call streaming differs across Workers AI models) and are replayed as SSE.
  const runStream = stream && !tools;
  if (runStream) inputs.stream = true;
  const json = { "Content-Type": "application/json; charset=utf-8" };
  try {
    const result = await Promise.race([
      ai.run(model, inputs),
      new Promise<never>((_, rej) => setTimeout(() => rej(new Error("timeout")), timeoutMs)),
    ]);
    if (runStream && result instanceof ReadableStream) {
      return { status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }), body: workersAIStreamToOpenAI(result as ReadableStream<Uint8Array>, model), errorText: "" };
    }
    const completion = fromWorkersAI(result, model);
    if (stream) return { status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }), body: completionToSSE(completion), errorText: "" };
    const bytes = enc.encode(JSON.stringify(completion));
    return { status: 200, headers: new Headers(json), body: new ReadableStream({ start(c) { c.enqueue(bytes); c.close(); } }), errorText: "" };
  } catch (e) {
    const msg = String((e as Error)?.message || e).slice(0, 300);
    return { status: workersAIErrorStatus(msg), headers: new Headers(), body: null, errorText: msg };
  }
}

/**
 * Passes an SSE/JSON body through untouched while noticing the token count the provider reports
 * ("total_tokens": N). Only that number is kept; the text itself is never stored.
 */
export function usageTap(src: ReadableStream<Uint8Array>, onTokens: (n: number) => void): ReadableStream<Uint8Array> {
  let tail = "";
  let best = 0;
  return src.pipeThrough(new TransformStream<Uint8Array, Uint8Array>({
    transform(bytes, ctl) {
      ctl.enqueue(bytes);
      tail = (tail + dec.decode(bytes, { stream: true })).slice(-4096);
      for (const m of tail.matchAll(/"total_tokens"\s*:\s*(\d+)/g)) best = Math.max(best, Number(m[1]));
    },
    flush() {
      if (best > 0) onTokens(best);
    },
  }));
}
