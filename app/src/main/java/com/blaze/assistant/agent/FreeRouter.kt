package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * "Auto (free routing)": spreads turns across the free tiers the user has keys for, the way
 * OmniRoute's fill-first combos do. Uses the first healthy model in the chain; on a rate limit,
 * server error, timeout or a model that can't use tools it cools that entry down and moves on.
 * Pollinations (keyless) is the last resort, for plain chat only.
 */
object FreeRouter {

    data class Health(val fails: Int = 0, val okCount: Int = 0, val coolUntil: Long = 0, val avgMs: Long = 0,
                      val lastError: String = "", val noTools: Boolean = false)

    private val health = ConcurrentHashMap<String, Health>()
    private val _status = MutableStateFlow<Map<String, Health>>(emptyMap())
    val status: StateFlow<Map<String, Health>> = _status

    private fun keyOf(t: LlmClient.Target) = t.pid + "|" + t.model

    // ---- Smart OpenRouter free pick ----
    // "openrouter/free" hands each turn to a random free model, sometimes a tiny coding model that
    // can't even read the clock. Instead pick the strongest free models that support tools, once a day.
    private val weak = Regex("""(code|coder|codestral|devstral|mini|nano|tiny|small|lite|micro|guard|embed|vision|-vl|ocr|reward|math|\b[0-9]b\b|\b(1\.5|0\.5|1|2|3|4|7|8|9|12|14)b\b|e2b|e4b|uncensored|roleplay|rp\b)""", RegexOption.IGNORE_CASE)
    private val strong = listOf("deepseek", "qwen3", "kimi", "glm", "gpt-oss", "llama-4", "gemini", "mistral-large", "nemotron-ultra", "hermes", "maverick", "grok")
    @Volatile private var refreshing = false

    /** Best free OpenRouter models (tools-capable), cached; refreshed in the background daily. */
    fun openRouterPicks(): List<String> {
        val saved = Settings.str("or_free_best", "")
        val at = Settings.str("or_free_best_at", "0").toLongOrNull() ?: 0
        if (System.currentTimeMillis() - at > 24 * 3600_000L && !refreshing) {
            refreshing = true
            Thread {
                try {
                    val (code, body) = com.blaze.assistant.connect.Http.req("GET", "https://openrouter.ai/api/v1/models", timeoutMs = 20_000)
                    if (code in 200..299) {
                        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        val c = (0 until data.length()).map { data.getJSONObject(it) }.filter { m ->
                            val id = m.optString("id")
                            val params = m.optJSONArray("supported_parameters")?.toString() ?: ""
                            id.endsWith(":free") && params.contains("\"tools\"") && !weak.containsMatchIn(id)
                        }.sortedWith(compareByDescending<JSONObject> { m -> strong.indexOfFirst { m.optString("id").contains(it, true) }.let { if (it < 0) 0 else strong.size - it } }
                            .thenByDescending { it.optInt("context_length") })
                        val top = c.take(3).map { it.optString("id") }
                        if (top.isNotEmpty()) { Settings.setStr("or_free_best", top.joinToString(",")); Settings.setStr("or_free_best_at", System.currentTimeMillis().toString()) }
                    }
                } catch (_: Exception) { } finally { refreshing = false }
            }.start()
        }
        return saved.split(',').filter { it.isNotBlank() }
    }

    /** Default free model per provider when the user hasn't picked one. */
    private fun defaultModel(pid: String) = when (pid) {
        "openrouter" -> openRouterPicks().firstOrNull() ?: "openrouter/free"
        "gemini" -> Settings.DEFAULT_MODEL
        else -> ""
    }

    /** The chain as saved ("pid|model" entries), or a default built from the keys that are set. */
    fun entries(): List<Pair<String, String>> {
        val saved = try {
            val a = JSONArray(Settings.freeChain)
            (0 until a.length()).map { a.getString(it).split('|', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } }
        } catch (_: Exception) { emptyList() }
        if (saved.isNotEmpty()) return saved
        return Providers.freeOrder.filter { Settings.keyFor(it).isNotBlank() }
            .map { it to Settings.modelFor(it).ifBlank { defaultModel(it) } }
            .filter { it.second.isNotBlank() }
    }

    fun save(list: List<Pair<String, String>>) {
        Settings.freeChain = JSONArray(list.map { "${it.first}|${it.second}" }).toString()
    }

    /** One-tap "free stack": every free-tier provider with a key, in the default order. */
    fun presetFreeStack() { Settings.freeChain = ""; save(entries()) }

    private fun chain(needTools: Boolean): List<LlmClient.Target> {
        val now = System.currentTimeMillis()
        val ts = entries().map { (pid, model) ->
            // A tiny or coding-only OpenRouter model gets swapped for today's best free pick.
            val m = if (pid == "openrouter" && (model == "openrouter/free" || weak.containsMatchIn(model))) openRouterPicks().firstOrNull() ?: model else model
            LlmClient.target(pid, m) }.filter { it.key.isNotBlank() && it.model.isNotBlank() }
        // Fill-first: keep the user's order, but skip cooled-down entries and push flaky ones back.
        val (ready, cooling) = ts.partition { (health[keyOf(it)]?.coolUntil ?: 0) <= now }
        var usable = ready.filterNot { needTools && health[keyOf(it)]?.noTools == true }
            .sortedBy { (health[keyOf(it)]?.fails ?: 0).coerceAtMost(3) }
        // Speed plan step 9: among equally healthy models, the one that has been fastest goes first
        // (an untried model counts as fast so it gets measured). Groq leads short chat turns.
        if (Settings.bool("fastest_first", true)) {
            usable = usable.sortedWith(compareBy<LlmClient.Target> { (health[keyOf(it)]?.fails ?: 0).coerceAtMost(3) }
                .thenBy { health[keyOf(it)]?.avgMs ?: 0L })
            if (LlmClient.quickTurn) usable = usable.sortedBy { if (it.pid == "groq") 0 else 1 }
        }
        val out = (usable + cooling.sortedBy { health[keyOf(it)]?.coolUntil ?: 0 }).toMutableList()
        if (!needTools || out.isEmpty()) out += LlmClient.target(Providers.keyless.id)
        return out
    }

    /** The first couple of models a turn would try, for connection pre-warming. */
    fun warmTargets(): List<LlmClient.Target> = try { chain(true).take(2) } catch (_: Exception) { emptyList() }

    private fun mark(t: LlmClient.Target, f: (Health) -> Health) {
        health[keyOf(t)] = f(health[keyOf(t)] ?: Health()); _status.value = HashMap(health)
    }

    suspend fun complete(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                         onThink: ((String) -> Unit)?, lane: LlmClient.Lane): JSONObject {
        val needTools = tools != null && tools.length() > 0
        val list = chain(needTools)
        if (list.isEmpty()) throw LlmClient.LlmException("Auto (free) has nothing to route to yet. Add a free key (Gemini, Groq, OpenRouter…) in Settings → AI.")
        var lastErr: Exception? = null
        for (t in list) {
            val keyless = t.pid == Providers.keyless.id
            val started = System.currentTimeMillis()
            try {
                val msg = LlmClient.call(t, messages, if (keyless) null else tools, onText,
                    if (lane == LlmClient.Lane.MAIN) onThink else null, false,
                    timeoutMs = if (list.size > 1) 45_000 else 120_000)
                val ms = System.currentTimeMillis() - started
                mark(t) { h -> h.copy(fails = 0, okCount = h.okCount + 1, coolUntil = 0, lastError = "",
                    avgMs = if (h.avgMs == 0L) ms else (h.avgMs * 3 + ms) / 4) }
                LlmClient.lastRoute = (if (keyless) "Keyless" else Providers.byId(t.pid).name.substringBefore(" (")) + " · " + t.model
                return msg
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                lastErr = e
                val code = (e as? LlmClient.LlmException)?.code ?: 0
                val text = e.message.orEmpty()
                val toolsProblem = code == 400 && (text.contains("tool", true) || text.contains("function", true))
                val cool = when {
                    code == 429 -> ((e as LlmClient.LlmException).retryAfterSec.takeIf { it > 0 } ?: 60) * 1000L
                    code == 401 || code == 403 || code == 404 -> 30 * 60_000L
                    code >= 500 || code == 0 -> 20_000L
                    else -> 10_000L
                }
                mark(t) { h -> h.copy(fails = h.fails + 1, coolUntil = System.currentTimeMillis() + cool,
                    lastError = text.take(120), noTools = h.noTools || toolsProblem) }
            }
        }
        throw LlmClient.LlmException("Every free model is busy or out of quota right now (last: ${lastErr?.message?.take(160)}). Try again in a minute or add another free key.")
    }
}
