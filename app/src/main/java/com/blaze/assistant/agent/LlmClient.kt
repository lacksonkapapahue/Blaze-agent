package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal OpenAI-compatible chat-completions client (works with Gemini, OpenAI, Groq, OpenRouter...). */
object LlmClient {

    class LlmException(msg: String, val code: Int = 0, val retryAfterSec: Int = 0) : Exception(msg)

    /** Which service + model one call goes to. */
    data class Target(val pid: String, val base: String, val key: String, val model: String) {
        val label get() = "${Providers.byId(pid).name.substringBefore(" (")} · $model"
    }

    fun target(pid: String, model: String? = null): Target {
        val p = Providers.byId(pid)
        val base = if (pid == Providers.CUSTOM) Settings.customBase else p.baseUrl
        return Target(pid, base, if (pid == Providers.keyless.id) "" else Settings.keyFor(pid),
            model?.takeIf { it.isNotBlank() } ?: Settings.modelFor(pid))
    }

    /** MAIN = the user's turn, STEPS = a turn right after a phone action, BACKGROUND = learning/summaries. */
    enum class Lane { MAIN, STEPS, BACKGROUND }

    /** The service + model that answered last (shown as the route chip). */
    @Volatile var lastRoute: String = ""

    /** Rate-limit headers from the latest reply (shown in Settings → Plan & quota). */
    @Volatile var lastRateHeaders: Map<String, String> = emptyMap()

    /** Auth headers for a provider. */
    fun authHeaders(key: String, pid: String = Settings.providerId): Map<String, String> =
        if (key.isBlank()) emptyMap()
        else if (pid == "anthropic") mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "Authorization" to "Bearer $key")
        else mapOf("Authorization" to "Bearer $key")

    fun rateHeaders(conn: HttpURLConnection): Map<String, String> =
        conn.headerFields.orEmpty().filterKeys { it != null && (it.lowercase().contains("ratelimit")) }
            .map { (k, v) -> k.lowercase() to (v.firstOrNull() ?: "") }.toMap()

    /** Token usage the provider reported for the last call, if any. */
    @Volatile var lastUsage: JSONObject? = null

    /** Set when the provider refused Gemini's include_thoughts option, so we stop sending it. */
    @Volatile private var thoughtsRefused = false

    /**
     * One model turn. With [onText], the reply streams in and [onText] gets the text so far.
     * Returns the assistant message in OpenAI shape (content + optional tool_calls).
     */
    suspend fun complete(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)? = null,
                         onThink: ((String) -> Unit)? = null, lane: Lane = Lane.MAIN): JSONObject {
        if (lane == Lane.STEPS && Settings.stepsProvider.isNotBlank() && Settings.stepsProvider != Providers.AUTO) {
            val t = target(Settings.stepsProvider, Settings.stepsModel)
            if (t.key.isNotBlank()) return call(t, messages, tools, onText, null, false).also { lastRoute = t.label }
        }
        // Daily spend cap: once today's estimated cost passes it, switch to the free chain.
        val cap = Settings.str("daily_cap", "").toDoubleOrNull()
        if (cap != null && cap > 0 && Settings.providerId != Providers.AUTO && try { Usage.today().cost >= cap } catch (_: Exception) { false })
            return FreeRouter.complete(messages, tools, onText, onThink, lane).also { lastRoute = "Free (daily cap) · " + lastRoute }
        if (Settings.providerId == Providers.AUTO || (lane == Lane.STEPS && Settings.stepsProvider == Providers.AUTO))
            return FreeRouter.complete(messages, tools, onText, onThink, lane)
        val t = target(Settings.providerId)
        val wantThoughts = lane == Lane.MAIN && Settings.showReasoning && !thoughtsRefused && t.pid == "gemini"
        return try { call(t, messages, tools, onText, onThink, wantThoughts).also { lastRoute = t.label } }
        catch (e: LlmException) {
            // Older Gemini models don't think; retry once without asking for thoughts.
            if (wantThoughts && (e.message ?: "").let { it.contains("thinking", true) || it.contains("extra_body", true) || it.contains("400") }) {
                thoughtsRefused = true; call(t, messages, tools, onText, onThink, false).also { lastRoute = t.label }
            } else throw e
        }
    }

    /** True for short, simple turns: ask reasoning models to think briefly so answers come fast. */
    @Volatile var quickTurn = false

    internal suspend fun call(t: Target, messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                              onThink: ((String) -> Unit)?, thoughts: Boolean, timeoutMs: Int = 120_000): JSONObject = withContext(Dispatchers.IO) {
        val key = t.key
        if (key.isBlank() && t.pid != Providers.keyless.id)
            throw LlmException("No API key for ${Providers.byId(t.pid).name} yet. Open Settings and paste your key.", 401)
        val base = t.base.trimEnd('/')
        if (base.isBlank()) throw LlmException("Add your service address in Settings.")
        if (t.model.isBlank()) throw LlmException("Pick a model in Settings → Model.", 404)
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.readTimeout = timeoutMs
        authHeaders(key, t.pid).forEach { (k, v) -> conn.setRequestProperty(k, v) }

        val body = JSONObject().apply {
            put("model", t.model)
            put("messages", messages)
            if (onText != null) put("stream", true)
            val quick = quickTurn && Settings.bool("quick_think", true)
            if (quick && t.pid == "openrouter") put("reasoning", JSONObject().put("effort", "low"))
            if (quick && t.pid == "gemini" && !thoughts) put("reasoning_effort", "low")
            if (thoughts) put("extra_body", JSONObject().put("google",
                JSONObject().put("thinking_config", JSONObject().put("include_thoughts", true).apply { if (quick) put("thinking_budget", 512) })))
            // OpenRouter: if this free model is busy, fall through to the next-best free ones server-side.
            if (t.pid == "openrouter" && t.model.endsWith(":free")) {
                val alts = FreeRouter.openRouterPicks().filter { it != t.model }.take(2)
                if (alts.isNotEmpty()) put("models", JSONArray(listOf(t.model) + alts))
            }
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        rateHeaders(conn).takeIf { it.isNotEmpty() }?.let { lastRateHeaders = it }
        lastUsage = null
        if (code in 200..299 && onText != null &&
            (conn.contentType ?: "").contains("event-stream", ignoreCase = true)) {
            try { return@withContext readStream(conn, onText, onThink) } finally { conn.disconnect() }
        }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val msg = try {
                val o = JSONObject(text)
                val e = o.opt("error")
                when (e) {
                    is JSONObject -> e.optString("message", text)
                    is JSONArray -> e.optJSONObject(0)?.optJSONObject("error")?.optString("message") ?: text
                    else -> text
                }
            } catch (_: Exception) { text }
            val retry = conn.getHeaderField("retry-after")?.trim()?.toIntOrNull() ?: 0
            if (code == 404 || msg.contains("no longer available", true) || msg.contains("not found", true))
                throw LlmException("The model “${t.model}” isn't available to your key. Open Settings → Model and pick one from the list.", 404)
            throw LlmException("The AI service said ($code): ${msg.take(300)}", code, retry)
        }
        val json = JSONObject(text)
        lastUsage = json.optJSONObject("usage")
        val m = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val r = m.optString("reasoning_content").ifBlank { m.optString("reasoning") }
        m.remove("reasoning_content"); m.remove("reasoning"); m.remove("reasoning_details")
        if (r.isNotBlank() && r != "null") m.put("_reasoning", r)
        m
    }

    /** Reads an SSE chat stream, stitching text and tool-call fragments back together. */
    private fun readStream(conn: HttpURLConnection, onText: (String) -> Unit, onThink: ((String) -> Unit)?): JSONObject {
        val content = StringBuilder()
        val think = StringBuilder()
        val calls = sortedMapOf<Int, JSONObject>()
        var lastEmit = 0L
        conn.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = try { JSONObject(data) } catch (_: Exception) { continue }
                obj.optJSONObject("usage")?.let { lastUsage = it }
                obj.optJSONObject("error")?.let { throw LlmException("The AI service said: " + it.optString("message").take(300)) }
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val rp = listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { k ->
                    delta.optString(k, "").takeIf { delta.has(k) && !delta.isNull(k) && it.isNotEmpty() } }
                if (rp != null) { think.append(rp); onThink?.invoke(think.toString()) }
                val piece = delta.optString("content", "").takeIf { delta.has("content") && !delta.isNull("content") }
                if (!piece.isNullOrEmpty()) {
                    content.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 90) { onText(content.toString()); lastEmit = now }
                }
                val tc = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until tc.length()) {
                    val d = tc.getJSONObject(i)
                    val idx = d.optInt("index", i)
                    val acc = calls.getOrPut(idx) {
                        JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", ""))
                    }
                    if (d.has("id") && !d.isNull("id")) acc.put("id", d.getString("id"))
                    d.optJSONObject("function")?.let { f ->
                        val af = acc.getJSONObject("function")
                        if (f.has("name") && !f.isNull("name")) af.put("name", af.getString("name") + f.getString("name"))
                        if (f.has("arguments") && !f.isNull("arguments")) af.put("arguments", af.getString("arguments") + f.getString("arguments"))
                    }
                    // Gemini sends a call whole; keep any extra fields (e.g. thought signatures) it adds.
                    d.optJSONObject("extra_content")?.let { acc.put("extra_content", it) }
                }
            }
        }
        if (content.isNotEmpty()) onText(content.toString())
        val msg = JSONObject().put("role", "assistant").put("content", if (content.isEmpty() && calls.isNotEmpty()) JSONObject.NULL else content.toString())
        if (think.isNotEmpty()) msg.put("_reasoning", think.toString())
        if (calls.isNotEmpty()) {
            val arr = JSONArray()
            calls.values.forEachIndexed { i, c -> if (!c.has("id")) c.put("id", "call_$i"); arr.put(c) }
            msg.put("tool_calls", arr)
        }
        return msg
    }
}
