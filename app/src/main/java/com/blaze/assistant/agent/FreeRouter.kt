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
        else -> Providers.freeDefault[pid] ?: ""
    }

    /** The chain as saved ("pid|model" entries), or a default built from the keys that are set. */
    fun entries(): List<Pair<String, String>> {
        val saved = try {
            val a = JSONArray(Settings.freeChain)
            (0 until a.length()).map { a.getString(it).split('|', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } }
        } catch (_: Exception) { emptyList() }.filter { it.first != "github" }   // GitHub Models retired Jul 2026
        val keyed = Providers.freeOrder.filter { Providers.keyOf(it).isNotBlank() }
            .map { it to Settings.modelFor(it).ifBlank { defaultModel(it) } }
            .filter { it.second.isNotBlank() }
        if (saved.isEmpty()) return keyed
        // A free key added after the chain was saved joins at the end, unless the user removed it.
        val removed = Settings.str("free_chain_removed", "").split(',').filter { it.isNotBlank() }.toSet()
        return saved + keyed.filter { k -> saved.none { it.first == k.first } && k.first !in removed }
    }

    /** The user removed [pid] from the chain: don't auto-add it back. */
    fun removed(pid: String) {
        val r = Settings.str("free_chain_removed", "").split(',').filter { it.isNotBlank() }.toMutableSet()
        r += pid; Settings.setStr("free_chain_removed", r.joinToString(","))
    }

    /** A key was just added for [pid] (sign-in or key catcher): let it join the chain again. */
    fun added(pid: String) {
        val r = Settings.str("free_chain_removed", "").split(',').filter { it.isNotBlank() && it != pid }
        Settings.setStr("free_chain_removed", r.joinToString(","))
    }

    fun save(list: List<Pair<String, String>>) {
        Settings.freeChain = JSONArray(list.map { "${it.first}|${it.second}" }).toString()
    }

    /** One-tap "free stack": every free-tier provider with a key, in the default order. */
    fun presetFreeStack() { Settings.freeChain = ""; Settings.setStr("free_chain_removed", ""); save(entries()) }

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
        // Chat lane: small fast models first, so the strong ones keep their quota for tool work.
        // Tool lane: strong models first.
        if (LlmClient.liteTurn) usable = usable.sortedBy { if (isLight(it)) 0 else 1 }
        else usable = usable.sortedBy { if (isLight(it) && usable.any { o -> !isLight(o) }) 1 else 0 }
        val out = (usable + cooling.sortedBy { health[keyOf(it)]?.coolUntil ?: 0 }).toMutableList()
        if (!needTools || out.isEmpty()) out += LlmClient.target(Providers.keyless.id)
        return out
    }

    private val light = Regex("(?i)(flash|mini|small|8b|9b|12b|scout|instant|lite|haiku|fast)")
    private fun isLight(t: LlmClient.Target) = t.pid == "groq" || t.pid == "cerebras" || light.containsMatchIn(t.model)

    /** The first couple of models a turn would try, for connection pre-warming. */
    fun warmTargets(): List<LlmClient.Target> = try { chain(true).take(2) } catch (_: Exception) { emptyList() }

    private fun mark(t: LlmClient.Target, f: (Health) -> Health) {
        health[keyOf(t)] = f(health[keyOf(t)] ?: Health()); _status.value = HashMap(health)
    }

    suspend fun complete(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                         onThink: ((String) -> Unit)?, lane: LlmClient.Lane, waited: Boolean = false): JSONObject {
        val needTools = tools != null && tools.length() > 0
        val list = chain(needTools)
        if (list.isEmpty()) throw LlmClient.LlmException("Auto (free) has nothing to route to yet. Add a free key (Gemini, Groq, OpenRouter…) in Settings → AI.")
        // Token meter: what this request costs, so a model without room is skipped before sending.
        val tokens = Budget.estimate(messages.toString().length + (tools?.toString()?.length ?: 0))
        var lastErr: Exception? = null
        val failures = mutableListOf<String>()
        val resting = mutableListOf<Triple<String, Long, String>>()   // name, ms until ready, why
        var allTooLarge = true
        var tried = 0
        for (t in list) {
            val keyless = t.pid == Providers.keyless.id
            val name = if (keyless) "Keyless" else Providers.byId(t.pid).name.substringBefore(" (")
            val blocked = if (keyless) null else Budget.wait(keyOf(t), tokens)
            if (blocked != null) {
                resting += Triple(name, blocked.first, blocked.second)
                allTooLarge = blocked.second.startsWith("request bigger") && allTooLarge
                continue
            }
            tried++
            val started = System.currentTimeMillis()
            try {
                if (!keyless) Budget.spend(keyOf(t), tokens)
                val msg = LlmClient.call(t, messages, if (keyless) null else tools, onText,
                    if (lane == LlmClient.Lane.MAIN) onThink else null, false,
                    timeoutMs = if (list.size > 1) 45_000 else 120_000)
                if (!keyless) {
                    Budget.headers(keyOf(t), LlmClient.lastCallHeaders)
                    LlmClient.lastUsage?.optInt("completion_tokens")?.takeIf { it > 0 }?.let { Budget.spend(keyOf(t), it) }
                }
                val ms = System.currentTimeMillis() - started
                mark(t) { h -> h.copy(fails = 0, okCount = h.okCount + 1, coolUntil = 0, lastError = "",
                    avgMs = if (h.avgMs == 0L) ms else (h.avgMs * 3 + ms) / 4) }
                LlmClient.lastRoute = "$name · ${t.model}"
                return msg
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                lastErr = e
                val code = (e as? LlmClient.LlmException)?.code ?: 0
                val text = e.message.orEmpty()
                if (!keyless) Budget.headers(keyOf(t), LlmClient.lastCallHeaders)
                if (!LlmClient.tooLarge(e) && !keyless) allTooLarge = false
                if (LlmClient.tooLarge(e)) { mark(t) { h -> h.copy(lastError = text.take(120)) }; failures += "$name: request too big for its plan"; continue }
                if (code == 429 || text.contains("quota", true) || text.contains("rate limit", true)) {
                    Budget.limited(keyOf(t), text, (e as? LlmClient.LlmException)?.retryAfterSec ?: 0)
                    Budget.wait(keyOf(t), 0)?.let { (ms, why) -> resting += Triple(name, ms, why) }
                    mark(t) { h -> h.copy(fails = h.fails + 1, coolUntil = System.currentTimeMillis() + (Budget.wait(keyOf(t), 0)?.first ?: 60_000L), lastError = text.take(120)) }
                    continue
                }
                failures += "$name: " + text.removePrefix("$name · ${t.model}: ").removePrefix("${Providers.byId(t.pid).name} · ${t.model}: ").take(110)
                val cool = when {
                    code == 401 || code == 403 || code == 404 -> 30 * 60_000L
                    code >= 500 || code == 0 -> 20_000L
                    else -> 10_000L
                }
                mark(t) { h -> h.copy(fails = h.fails + 1, coolUntil = System.currentTimeMillis() + cool,
                    lastError = text.take(120), noTools = h.noTools || (code == 400 && (text.contains("tool", true) || text.contains("function", true)))) }
            }
        }
        // Every model said the request was too big: trim the conversation once and go round again.
        if (allTooLarge && (lastErr != null || resting.isNotEmpty())) {
            val small = LlmClient.shrink(messages, 6)
            if (small.length() < messages.length()) return complete(small, tools, onText, onThink, lane, waited)
        }
        // Everyone is resting but one is back within ~25 s: wait quietly once instead of failing.
        val soonest = resting.minByOrNull { it.second }
        if (!waited && soonest != null && soonest.second <= 25_000 && failures.isEmpty()) {
            restingNote = "${soonest.first} is cooling down, back in ${(soonest.second / 1000).coerceAtLeast(1)}s"
            kotlinx.coroutines.delay(soonest.second + 300)
            restingNote = ""
            return complete(messages, tools, onText, onThink, lane, true)
        }
        val code0 = (lastErr as? LlmClient.LlmException)?.code ?: if (resting.isNotEmpty()) 429 else 0
        val ra = ((soonest?.second ?: 0L) / 1000).toInt()
        val parts = resting.distinctBy { it.first }.sortedBy { it.second }.map { "${it.first} back in ${fmt(it.second)} (${it.third})" } + failures.distinct()
        throw LlmClient.LlmException(
            (if (failures.isEmpty()) "All your free models are resting right now:\n" else "No model in your Auto chain could answer:\n") +
                parts.take(6).joinToString("\n") { "• $it" } +
                (if (failures.isEmpty()) "\nTry again ${if (soonest != null) "in ${fmt(soonest.second)}" else "soon"}, or add another free key (NVIDIA, OpenRouter, Cerebras, Mistral, GitHub) in Settings → AI so I can switch."
                 else "\nFix the one with a key problem in Settings → AI, or try again in a minute."),
            code0, ra)
    }

    /** Shown while Auto waits for a cooling model (empty otherwise). */
    @Volatile var restingNote: String = ""

    private fun fmt(ms: Long): String = when {
        ms >= 3600_000 -> "${ms / 3600_000}h ${(ms % 3600_000) / 60_000}m"
        ms >= 90_000 -> "${(ms + 59_999) / 60_000} min"
        else -> "${(ms + 999) / 1000}s"
    }
}
