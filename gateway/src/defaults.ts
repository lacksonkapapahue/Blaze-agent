// What a fresh gateway starts with. All of it is editable in /admin; "Reset to defaults" on the
// routes page puts these back. Model ids follow each provider's own naming.

import type { Task } from "./router";

export type ProviderType = "openai" | "workers-ai";

export interface ProviderSeed {
  id: string;
  name: string;
  base_url: string;
  type: ProviderType;
  enabled: boolean;
  notes: string;
}

export const WORKERS_AI = "workers-ai";

export const DEFAULT_PROVIDERS: ProviderSeed[] = [
  { id: "gemini", name: "Google Gemini", base_url: "https://generativelanguage.googleapis.com/v1beta/openai", type: "openai", enabled: true,
    notes: "Key: aistudio.google.com/apikey. Free quota: Google may use prompts/answers to improve products (human review); free tier not allowed for apps serving EEA/UK/CH users; users must be 18+." },
  { id: "groq", name: "Groq", base_url: "https://api.groq.com/openai/v1", type: "openai", enabled: true,
    notes: "Key: console.groq.com/keys. Free plan, per-model rate limits. No inference data kept by default; turn on Zero Data Retention in Data Controls." },
  { id: "cerebras", name: "Cerebras", base_url: "https://api.cerebras.ai/v1", type: "openai", enabled: true,
    notes: "Key: cloud.cerebras.ai. Free tier is 'for development, evaluation, and experimentation; not intended for production use'." },
  { id: "openrouter", name: "OpenRouter (free models)", base_url: "https://openrouter.ai/api/v1", type: "openai", enabled: true,
    notes: "Key: openrouter.ai/settings/keys. :free models only: 20/min and 50/day per account (1,000/day after a one-time $10 top-up). Some free endpoints log prompts for training." },
  { id: "mistral", name: "Mistral", base_url: "https://api.mistral.ai/v1", type: "openai", enabled: true,
    notes: "Key: console.mistral.ai. Free (Experiment) plan: requests may be used to train Mistral models unless you opt out." },
  { id: "github", name: "GitHub Models", base_url: "https://models.github.ai/inference", type: "openai", enabled: false,
    notes: "GitHub Models was fully retired on 2026-07-30; kept disabled so the adapter slot exists. Don't enable." },
  { id: "nvidia", name: "NVIDIA NIM", base_url: "https://integrate.api.nvidia.com/v1", type: "openai", enabled: false,
    notes: "Disabled by default: free NIM API access is aimed at prototyping (terms not re-checked for 0.1.0). Key: build.nvidia.com." },
  { id: WORKERS_AI, name: "Cloudflare Workers AI", base_url: "binding:AI", type: "workers-ai", enabled: true,
    notes: "No key: uses the Worker's AI binding. 10,000 Neurons/day free. Last-resort fallback." },
];

export interface Candidate {
  provider: string;
  model: string;
}

const c = (provider: string, model: string): Candidate => ({ provider, model });

const CF_BIG = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const CF_SMALL = "@cf/meta/llama-3.1-8b-instruct-fast";
const CF_SCOUT = "@cf/meta/llama-4-scout-17b-16e-instruct";
const CF_CODE = "@cf/qwen/qwen2.5-coder-32b-instruct";

/** Ordered model list per task tag. Workers AI is always last. */
export const DEFAULT_ROUTES: Record<Task, Candidate[]> = {
  chat: [
    c("gemini", "gemini-flash-latest"),
    c("groq", "llama-3.3-70b-versatile"),
    c("cerebras", "gpt-oss-120b"),
    c("mistral", "mistral-medium-latest"),
    c("openrouter", "deepseek/deepseek-chat-v3.1:free"),
    c("groq", "openai/gpt-oss-120b"),
    c(WORKERS_AI, CF_BIG),
  ],
  quick: [
    c("groq", "llama-3.1-8b-instant"),
    c("cerebras", "llama3.1-8b"),
    c("gemini", "gemini-flash-lite-latest"),
    c("mistral", "ministral-8b-latest"),
    c(WORKERS_AI, CF_SMALL),
  ],
  code: [
    c("cerebras", "gpt-oss-120b"),
    c("groq", "openai/gpt-oss-120b"),
    c("gemini", "gemini-flash-latest"),
    c("mistral", "codestral-latest"),
    c("openrouter", "qwen/qwen3-coder:free"),
    c(WORKERS_AI, CF_CODE),
  ],
  vision: [
    c("gemini", "gemini-flash-latest"),
    c("groq", "meta-llama/llama-4-scout-17b-16e-instruct"),
    c("mistral", "mistral-small-latest"),
    c("openrouter", "google/gemma-3-27b-it:free"),
    c(WORKERS_AI, CF_SCOUT),
  ],
  long: [
    c("gemini", "gemini-flash-latest"),
    c("gemini", "gemini-flash-lite-latest"),
    c("mistral", "mistral-medium-latest"),
    c("groq", "moonshotai/kimi-k2-instruct-0905"),
    c("openrouter", "deepseek/deepseek-chat-v3.1:free"),
    c(WORKERS_AI, CF_SCOUT),
  ],
  agent: [
    c("gemini", "gemini-flash-latest"),
    c("groq", "openai/gpt-oss-120b"),
    c("cerebras", "gpt-oss-120b"),
    c("groq", "moonshotai/kimi-k2-instruct-0905"),
    c("mistral", "mistral-medium-latest"),
    c(WORKERS_AI, CF_SCOUT),
  ],
};

export interface PlanLimits {
  id: string;
  /** Monthly price shown in /admin. Display only: billing is out of scope for 0.1.0. */
  price_usd: number;
  daily_requests: number;
  /** 0 = no token cap. Tokens are counted when the provider reports usage. */
  daily_tokens: number;
}

/** Every account starts on this plan unless /admin assigns another to its email. */
export const DEFAULT_PLAN = "basic";

export const DEFAULT_PLANS: PlanLimits[] = [
  { id: "basic", price_usd: 0, daily_requests: 150, daily_tokens: 300_000 },
  { id: "standard", price_usd: 10, daily_requests: 1_000, daily_tokens: 2_000_000 },
  { id: "pro", price_usd: 20, daily_requests: 5_000, daily_tokens: 0 },
];
