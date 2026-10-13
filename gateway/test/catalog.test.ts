import { describe, it, expect } from "vitest";
import {
  CURATED_WORKERS_AI, friendly, inferCaps, isRoutable, parseGemini, parseOpenAIList, parseWorkersAI, prettify, suggestLineup, suggestTask, type ModelInfo,
} from "../src/catalog";

describe("catalog parsers", () => {
  it("Gemini: keeps generateContent chat models, drops embeddings / images / tts, flags paid-only", () => {
    const ms = parseGemini({ models: [
      { name: "models/gemini-flash-latest", displayName: "Gemini Flash Latest", inputTokenLimit: 1048576, supportedGenerationMethods: ["generateContent", "countTokens"] },
      { name: "models/gemini-2.5-flash-lite", displayName: "Gemini 2.5 Flash-Lite", inputTokenLimit: 1048576, supportedGenerationMethods: ["generateContent"] },
      { name: "models/gemini-embedding-001", supportedGenerationMethods: ["embedContent"] },
      { name: "models/gemini-2.5-flash-image", supportedGenerationMethods: ["generateContent"] },
      { name: "models/gemini-2.5-flash-preview-tts", supportedGenerationMethods: ["generateContent"] },
      { name: "models/gemini-3-pro-preview", displayName: "Gemini 3 Pro Preview", inputTokenLimit: 1048576, supportedGenerationMethods: ["generateContent"] },
      { name: "models/gemma-3-27b-it", displayName: "Gemma 3 27B", inputTokenLimit: 131072, supportedGenerationMethods: ["generateContent"] },
    ] });
    expect(ms.map(m => m.id)).toEqual(["gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-3-pro-preview", "gemma-3-27b-it"]);
    const flash = ms[0]!;
    expect(flash.caps).toEqual(expect.arrayContaining(["chat", "vision", "fast", "long", "tools"]));
    expect(flash.name).toBe("Gemini Flash Latest (fast, sees images, long inputs)");
    expect(ms.find(m => m.id === "gemini-3-pro-preview")!.free).toBe(false);
    expect(ms.find(m => m.id === "gemma-3-27b-it")!.caps).not.toContain("tools");
  });
  it("OpenRouter: only :free models survive", () => {
    const ms = parseOpenAIList("openrouter", { data: [
      { id: "deepseek/deepseek-chat-v3.1:free", name: "DeepSeek: DeepSeek V3.1 (free)", context_length: 163840, pricing: { prompt: "0", completion: "0" }, architecture: { input_modalities: ["text"], output_modalities: ["text"] }, supported_parameters: ["tools"] },
      { id: "google/gemma-3-27b-it:free", name: "Google: Gemma 3 27B (free)", context_length: 96000, pricing: { prompt: "0", completion: "0" }, architecture: { input_modalities: ["text", "image"], output_modalities: ["text"] }, supported_parameters: [] },
      { id: "openai/gpt-4o", name: "OpenAI: GPT-4o", pricing: { prompt: "0.0000025", completion: "0.00001" } },
    ] });
    expect(ms.map(m => m.id)).toEqual(["deepseek/deepseek-chat-v3.1:free", "google/gemma-3-27b-it:free"]);
    expect(ms[0]!.name).toBe("DeepSeek V3.1 (good at code, long inputs)");
    expect(ms[0]!.caps).toContain("tools");
    expect(ms[1]!.caps).toContain("vision");
    expect(ms[1]!.caps).not.toContain("tools");
  });
  it("Mistral: uses capabilities; drops embed / ocr / moderation", () => {
    const ms = parseOpenAIList("mistral", { data: [
      { id: "mistral-small-latest", max_context_length: 131072, capabilities: { completion_chat: true, function_calling: true, vision: true } },
      { id: "codestral-latest", max_context_length: 256000, capabilities: { completion_chat: true, function_calling: true, vision: false } },
      { id: "mistral-embed", capabilities: { completion_chat: false } },
      { id: "mistral-ocr-latest", capabilities: { completion_chat: false } },
      { id: "mistral-moderation-latest", capabilities: { completion_chat: false } },
    ] });
    expect(ms.map(m => m.id)).toEqual(["mistral-small-latest", "codestral-latest"]);
    expect(ms[1]!.caps).toEqual(["chat", "code", "long", "tools"]);
  });
  it("Groq: drops whisper / guard / tts and inactive models", () => {
    const ms = parseOpenAIList("groq", { data: [
      { id: "llama-3.1-8b-instant", context_window: 131072, active: true },
      { id: "whisper-large-v3", active: true },
      { id: "meta-llama/llama-guard-4-12b", active: true },
      { id: "playai-tts", active: true },
      { id: "old-model", active: false },
      { id: "meta-llama/llama-4-scout-17b-16e-instruct", context_window: 131072, active: true },
    ] });
    expect(ms.map(m => m.id)).toEqual(["llama-3.1-8b-instant", "meta-llama/llama-4-scout-17b-16e-instruct"]);
    expect(ms[1]!.caps).toContain("vision");
    expect(ms[0]!.caps).toContain("fast");
  });
  it("Workers AI: catalogue and the curated fallback", () => {
    const ms = parseWorkersAI([
      { name: "@cf/meta/llama-3.3-70b-instruct-fp8-fast", task: { name: "Text Generation" }, properties: [{ property_id: "context_window", value: "24000" }, { property_id: "function_calling", value: "true" }] },
      { name: "@cf/baai/bge-m3", task: { name: "Text Embeddings" } },
      { name: "@cf/black-forest-labs/flux-1-schnell", task: { name: "Text-to-Image" } },
    ]);
    expect(ms.map(m => m.id)).toEqual(["@cf/meta/llama-3.3-70b-instruct-fp8-fast"]);
    expect(ms[0]!.name).toBe("Llama 3.3 70B Fast (fast)");
    expect(parseWorkersAI(CURATED_WORKERS_AI).length).toBeGreaterThan(10);
  });
  it("friendly names", () => {
    expect(prettify("@cf/qwen/qwen2.5-coder-32b-instruct")).toBe("Qwen2.5 Coder 32B");
    expect(friendly("Gemini Flash", ["chat", "vision", "fast"])).toBe("Gemini Flash (fast, sees images)");
    expect(inferCaps("cerebras", "gpt-oss-120b")).toEqual(["chat", "code", "tools"]);
  });
});

describe("free-only rule and lineup", () => {
  it("never routes to paid models", () => {
    expect(isRoutable({ provider: "openrouter", model: "openai/gpt-4o" }, new Set())).toBe(false);
    expect(isRoutable({ provider: "openrouter", model: "x/y:free" }, new Set())).toBe(true);
    expect(isRoutable({ provider: "gemini", model: "gemini-3-pro-preview" }, new Set(["gemini|gemini-3-pro-preview"]))).toBe(false);
  });
  const m = (provider: string, id: string, caps: ModelInfo["caps"], context = 0, free = true): ModelInfo => ({ provider, id, name: id, caps: ["chat", ...caps], context, free });
  const models: ModelInfo[] = [
    m("gemini", "gemini-flash-latest", ["vision", "fast", "long", "tools"], 1_000_000),
    m("gemini", "gemini-flash-lite-latest", ["vision", "fast", "long", "tools"], 1_000_000),
    m("gemini", "gemini-3-pro-preview", ["vision", "long", "tools"], 1_000_000, false),
    m("mistral", "mistral-medium-latest", ["vision", "long", "tools"], 131072),
    m("mistral", "codestral-latest", ["code", "long", "tools"], 256000),
    m("mistral", "ministral-8b-latest", ["fast", "long", "tools"], 131072),
    m("groq", "llama-3.3-70b-versatile", ["long", "tools"], 131072),
    m("workers-ai", "@cf/meta/llama-3.3-70b-instruct-fp8-fast", ["fast", "tools"], 24000),
    m("workers-ai", "@cf/meta/llama-4-scout-17b-16e-instruct", ["vision", "long", "tools"], 131000),
  ];
  const usable = (p: string) => p !== "groq"; // no Groq key yet
  it("fills every task from usable, free models; Workers AI last; vision only sees-images models", () => {
    const l = suggestLineup(models, usable);
    for (const t of Object.keys(l) as (keyof typeof l)[]) {
      expect(l[t].length).toBeGreaterThan(1);
      expect(l[t][l[t].length - 1]!.provider).toBe("workers-ai");
      expect(l[t].some(c => c.provider === "groq")).toBe(false);
      expect(l[t].some(c => c.model === "gemini-3-pro-preview")).toBe(false);
    }
    expect(l.vision.every(c => models.find(x => x.id === c.model)!.caps.includes("vision"))).toBe(true);
    expect(l.vision[l.vision.length - 1]!.model).toBe("@cf/meta/llama-4-scout-17b-16e-instruct");
    expect(l.chat[0]).toEqual({ provider: "gemini", model: "gemini-flash-latest" });
    expect(l.code.slice(0, 3).some(c => c.model === "codestral-latest")).toBe(true);
    expect(l.quick[0]!.model).not.toBe("mistral-medium-latest");
  });
  it("at most two per provider", () => {
    const many = [...models, m("gemini", "gemini-2.5-flash", ["vision", "fast", "long", "tools"], 1_000_000)];
    expect(suggestTask("chat", many, () => true).filter(c => c.provider === "gemini").length).toBe(2);
  });
});
