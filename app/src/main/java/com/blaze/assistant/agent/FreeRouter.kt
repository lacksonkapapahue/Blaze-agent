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

    /** Default free model per provider when the user hasn't picked one. */
    private fun defaultModel(pid: String) = when (pid) {
        "openrouter" -> "openrouter/free"
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
        val ts = entries().map { LlmClient.target(it.first, it.second) }.filter { it.key.isNotBlank() && it.model.isNotBlank() }
        // Fill-first: keep the user's order, but skip cooled-down entries and push flaky ones back.
        val (ready, cooling) = ts.partition { (health[keyOf(it)]?.coolUntil ?: 0) <= now }
        val usable = ready.filterNot { needTools && health[keyOf(it)]?.noTools == true }
            .sortedBy { (health[keyOf(it)]?.fails ?: 0).coerceAtMost(3) }
        val out = (usable + cooling.sortedBy { health[keyOf(it)]?.coolUntil ?: 0 }).toMutableList()
        if (!needTools || out.isEmpty()) out += LlmClient.target(Providers.keyless.id)
        return out
    }

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
