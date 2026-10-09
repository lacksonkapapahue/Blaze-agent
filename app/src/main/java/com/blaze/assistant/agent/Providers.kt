package com.blaze.assistant.agent

/** Built-in AI services. All speak the OpenAI chat-completions format at [baseUrl]. */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val keyPage: String,
    val quotaPage: String,
)

object Providers {
    const val CUSTOM = "custom"
    const val AUTO = "auto"
    /** Keyless public endpoint used as the very last resort (chat only, no tools). */
    val keyless = Provider("pollinations", "Pollinations (keyless)", "https://text.pollinations.ai/openai/",
        "openai-fast", "", "", "")

    val all = listOf(
        Provider(AUTO, "Auto (free routing)", "", "", "", "", ""),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/",
            "gemini-3.8-flash", "AIza…", "https://aistudio.google.com/apikey",
            "https://aistudio.google.com/rate-limit?timeRange=last-28-days"),
        Provider("openai", "ChatGPT (OpenAI)", "https://api.openai.com/v1/",
            "", "sk-…", "https://platform.openai.com/api-keys", "https://platform.openai.com/settings/organization/limits"),
        Provider("anthropic", "Claude (Anthropic)", "https://api.anthropic.com/v1/",
            "", "sk-ant-…", "https://console.anthropic.com/settings/keys", "https://console.anthropic.com/settings/limits"),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1/",
            "", "nvapi-…", "https://build.nvidia.com/settings/api-keys", "https://build.nvidia.com/"),
        Provider("moonshot", "Kimi (Moonshot)", "https://api.moonshot.ai/v1/",
            "", "sk-…", "https://platform.moonshot.ai/console/api-keys", "https://platform.moonshot.ai/console/limits"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1/",
            "deepseek-chat", "sk-…", "https://platform.deepseek.com/api_keys", "https://platform.deepseek.com/usage"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1/",
            "", "gsk_…", "https://console.groq.com/keys", "https://console.groq.com/settings/limits"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/",
            "", "sk-or-…", "https://openrouter.ai/settings/keys", "https://openrouter.ai/settings/credits"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1/",
            "", "…", "https://console.mistral.ai/api-keys", "https://admin.mistral.ai/plateforme/limits"),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1/",
            "", "csk-…", "https://cloud.cerebras.ai/platform", "https://cloud.cerebras.ai/platform"),
        Provider("github", "GitHub Models", "https://models.github.ai/inference/",
            "", "github_pat_… (or connect GitHub)", "https://github.com/settings/personal-access-tokens/new", "https://github.com/marketplace/models"),
        Provider("xai", "Grok (xAI)", "https://api.x.ai/v1/",
            "", "xai-…", "https://console.x.ai/", "https://console.x.ai/"),
        Provider(CUSTOM, "Custom (OpenAI-compatible)", "", "", "Your key", "", ""),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: if (id == keyless.id) keyless else all[1]

    /** Providers with a real free tier, in the default fill-first order for Auto. */
    val freeOrder = listOf("gemini", "groq", "cerebras", "nvidia", "openrouter", "mistral", "github", "deepseek", "moonshot")

    /** Free-tier model Auto uses for a provider when the user hasn't picked one. A 404 cools it for 30 min. */
    val freeDefault = mapOf(
        "groq" to "llama-3.3-70b-versatile",
        "cerebras" to "gpt-oss-120b",
        "nvidia" to "meta/llama-3.3-70b-instruct",
        "mistral" to "mistral-small-latest",
        "github" to "openai/gpt-4.1-mini",
    )

    /**
     * The key Blaze would use for [pid]. GitHub Models also accepts the token of the GitHub
     * account connected in Settings → Connections, so no separate key is needed there.
     */
    fun keyOf(pid: String): String {
        val k = Settings.keyFor(pid)
        if (k.isNotBlank() || pid != "github") return k
        return try { com.blaze.assistant.connect.Connectors.access("github").orEmpty() } catch (_: Exception) { "" }
    }
}
