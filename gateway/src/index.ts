// Blaze Gateway: the Worker entry point.
//   GET  /health                    {"ok":true,"service":"blaze-gateway","version":"0.3.0"}
//   GET  /v1/models                 the task-tagged virtual models (Blaze account token)
//   POST /v1/chat/completions       OpenAI-compatible, streaming or not (Blaze account token)
//                                   Accounts not approved get 403 waitlisted / access_rejected (src/access.ts)
//   GET  /admin                     the admin dashboard; /admin/api/* behind ADMIN_EMAILS / ADMIN_TOKEN

import { handleAdminApi } from "./admin";
import { adminPage } from "./admin-page";
import { requireAccess } from "./access";
import { requireUser } from "./auth";
import { discoverAll } from "./catalog";
import { chatCompletions } from "./chat";
import { loadConfig } from "./db";
import { VERSION, type Env } from "./env";
import { TASKS } from "./router";
import { CORS, HttpError, apiError, json } from "./util";

const MODEL_INFO: Record<string, string> = {
  auto: "Blaze picks the task from the request",
  chat: "Everyday conversation",
  quick: "Short, fast answers",
  code: "Programming",
  vision: "Images in the conversation",
  long: "Very long inputs",
  agent: "Tool / function calling",
};

function models(): Response {
  const created = 1760313600; // 2025-10-13, fixed so the list is stable
  const data = ["auto", ...TASKS].map(t => ({ id: `blaze-${t}`, object: "model", created, owned_by: "blaze", description: MODEL_INFO[t] }));
  return json({ object: "list", data }, 200, CORS);
}

function nonce(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...b));
}

async function handle(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const m = req.method;

  if (m === "OPTIONS" && (path.startsWith("/v1/") || path === "/v1")) return new Response(null, { status: 204, headers: { ...CORS, "Access-Control-Max-Age": "86400" } });
  if (path === "/" || path === "/health") return json({ ok: true, service: "blaze-gateway", version: VERSION });
  if (path === "/v1/models" && m === "GET") { await requireAccess(env, await requireUser(env, req)); return models(); }
  if (path === "/v1/chat/completions" && m === "POST") return chatCompletions(req, env, ctx);
  if (path === "/admin" && m === "GET") {
    const n = nonce();
    return new Response(adminPage(n), {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store",
        "Content-Security-Policy": `default-src 'none'; script-src 'nonce-${n}'; style-src 'nonce-${n}'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
      },
    });
  }
  if (path.startsWith("/admin/api/")) return handleAdminApi(req, env, path);
  throw new HttpError(404, "not_found", "Not found");
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      return await handle(req, env, ctx);
    } catch (e) {
      if (e instanceof HttpError) return apiError(e.status, e.code, e.message, e.headers);
      // Never echo internals (or anything from the request) in a 500.
      console.error("gateway error", (e as Error)?.name || "Error");
      return apiError(500, "internal_error", "Something went wrong in the gateway");
    }
  },
  /** Cron (wrangler.toml): model discovery for providers whose list is about a day old. */
  async scheduled(_ev: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil((async () => {
      try {
        const cfg = await loadConfig(env.DB);
        await discoverAll(env, [...cfg.providers.values()], cfg.keys, { onlyStale: true });
      } catch (e) {
        console.error("discovery cron", (e as Error)?.name || "Error");
      }
    })());
  },
} satisfies ExportedHandler<Env>;
