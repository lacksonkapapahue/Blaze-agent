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
        return Target(pid, base, if (pid == Providers.keyless.id) "" else Providers.keyOf(pid),
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

    /** Rate-limit headers of the very last call (empty when it sent none), for the token meter. */
    @Volatile var lastCallHeaders: Map<String, String> = emptyMap()

    /** True for a chat-lane turn (no tools, short prompt): Auto prefers small fast models for it. */
    @Volatile var liteTurn = false

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
        // Every request goes out clean (each tool call answered once, valid JSON, no orphans).
        // If the provider still refuses its shape (400 INVALID_ARGUMENT and friends), retry once
        // with the tool turns flattened to plain text, which every provider accepts.
        val clean = try { ToolCalls.clean(messages) } catch (_: Exception) { messages }
        return try { completeRaw(clean, tools, onText, onThink, lane) }
        catch (e: LlmException) {
            if (!ToolCalls.isShapeError(e)) throw e
            lastHealed = e.message.orEmpty()
            try { completeRaw(ToolCalls.flatten(clean), tools, onText, onThink, lane).also { lastRoute = "$lastRoute · healed" } }
            catch (e2: LlmException) { throw LlmException(ToolCalls.friendly(e2) + "\n\n(" + (e2.message ?: "").take(200) + ")", e2.code, e2.retryAfterSec) }
        }
    }

    /** The provider error the last self-heal recovered from (diagnostics). */
    @Volatile var lastHealed: String = ""

    private suspend fun completeRaw(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                         onThink: ((String) -> Unit)?, lane: Lane): JSONObject {
        // Steps model: a separate, optional model for in-between phone turns. If it fails, the
        // turn falls back to the user's main model instead of surfacing another provider's error.
        val mainAuto = Settings.providerId == Providers.AUTO
        if (lane == Lane.STEPS && Settings.stepsProvider.isNotBlank() && Settings.stepsProvider != Settings.providerId) {
            try {
                if (Settings.stepsProvider == Providers.AUTO) return FreeRouter.complete(messages, tools, onText, onThink, lane)
                val t = target(Settings.stepsProvider, Settings.stepsModel)
                if (t.key.isNotBlank()) return call(t, messages, tools, onText, null, false).also { lastRoute = t.label }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { if (mainAuto) throw e; lastStepsError = e.message.orEmpty() }
        }
        // Daily spend cap: once today's estimated cost passes it, switch to the free chain.
        val cap = Settings.str("daily_cap", "").toDoubleOrNull()
        if (cap != null && cap > 0 && Settings.providerId != Providers.AUTO && try { Usage.today().cost >= cap } catch (_: Exception) { false })
            return FreeRouter.complete(messages, tools, onText, onThink, lane).also { lastRoute = "Free (daily cap) · " + lastRoute }
        if (mainAuto) return FreeRouter.complete(messages, tools, onText, onThink, lane)
        val t = target(Settings.providerId)
        val wantThoughts = lane == Lane.MAIN && Settings.showReasoning && !thoughtsRefused && t.pid == "gemini"
        return try { call(t, messages, tools, onText, onThink, wantThoughts).also { lastRoute = t.label } }
        catch (e: LlmException) {
            // Older Gemini models don't think; retry once without asking for thoughts.
            if (wantThoughts && (e.message ?: "").let { it.contains("thinking", true) || it.contains("extra_body", true) || it.contains("400") }) {
                thoughtsRefused = true; call(t, messages, tools, onText, onThink, false).also { lastRoute = t.label }
            } else if (tooLarge(e)) {
                // The conversation outgrew this model's limit: retry once with a trimmed copy.
                call(t, shrink(messages), tools, onText, onThink, false).also { lastRoute = t.label + " · trimmed" }
            } else throw e
        }
    }

    /** Why the optional steps model last failed (shown in diagnostics); the main model took over. */
    @Volatile var lastStepsError: String = ""

    /** Wall-clock time of the latest streamed chunk (text, thinking or tool call), for the hang watchdog. */
    @Volatile var lastChunkAt: Long = 0

    /** True when a provider refused the request for being too big for the model or the plan. */
    fun tooLarge(e: Exception): Boolean {
        val code = (e as? LlmException)?.code ?: 0
        val m = e.message.orEmpty()
        return code == 413 || m.contains("too large", true) || m.contains("context length", true) || m.contains("context_length", true) ||
            m.contains("maximum context", true) || m.contains("too many tokens", true) || m.contains("reduce the length", true)
    }

    /** Keeps the system prompt and the recent turns, and cuts very long messages, so a request fits. */
    fun shrink(messages: JSONArray, keep: Int = 10): JSONArray {
        val all = (0 until messages.length()).map { messages.getJSONObject(it) }
        val sys = all.takeWhile { it.optString("role") == "system" }
        var tail = all.drop(sys.size).takeLast(keep)
        // Never start on a tool result whose call was cut off.
        while (tail.isNotEmpty() && tail.first().optString("role") == "tool") tail = tail.drop(1)
        val out = JSONArray()
        (sys + tail).forEach { m ->
            val c = JSONObject(m.toString())
            val content = c.opt("content")
            if (content is String && content.length > 6000) c.put("content", content.take(2500) + "\n…[trimmed]…\n" + content.takeLast(2500))
            out.put(c)
        }
        return out
    }

    /** Which provider a key's format belongs to, when the prefix is unmistakable. */
    fun keyOwner(key: String): String? {
        val k = key.trim()
        return when {
            k.startsWith("nvapi-") -> "nvidia"
            k.startsWith("gsk_") -> "groq"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("AIza") -> "gemini"
            k.startsWith("sk-ant-") -> "anthropic"
            k.startsWith("xai-") -> "xai"
            k.startsWith("csk-") -> "cerebras"
            else -> null
        }
    }

    /** Milliseconds from sending the request to the first streamed token (or the whole reply) of the last call. */
    @Volatile var lastFirstTokenMs: Long = 0
    @Volatile private var callStart: Long = 0
    @Volatile private var gotFirst = false
    /** Wall-clock time of that first token. */
    @Volatile var lastFirstTokenAt: Long = 0
    private fun markFirst() { if (!gotFirst) { gotFirst = true; lastFirstTokenAt = System.currentTimeMillis(); lastFirstTokenMs = lastFirstTokenAt - callStart } }

    private val warmed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Pre-warm (speed plan step 10): resolve DNS and open the TLS connection to the AI service
     * while the user is still talking, so the real request skips that handshake. Kept-alive
     * connections are reused by HttpURLConnection's pool. At most once a minute per host.
     */
    fun prewarm() {
        val bases = try {
            if (Settings.providerId == Providers.AUTO) FreeRouter.warmTargets().map { it.base }
            else listOf(target(Settings.providerId).base)
        } catch (_: Exception) { emptyList() }
        for (b in bases.map { it.trimEnd('/') }.filter { it.startsWith("http") }.distinct().take(2)) {
            val host = try { URL(b).host } catch (_: Exception) { continue }
            val now = System.currentTimeMillis()
            if (now - (warmed[host] ?: 0L) < 60_000) continue
            warmed[host] = now
            Thread {
                try {
                    val c = URL(b).openConnection() as HttpURLConnection
                    c.requestMethod = "HEAD"; c.connectTimeout = 5000; c.readTimeout = 5000
                    c.responseCode; c.inputStream.close()
                } catch (_: Exception) { }
            }.start()
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
        val owner = keyOwner(key)
        if (owner != null && owner != t.pid && t.pid != Providers.CUSTOM && t.pid != Providers.keyless.id)
            throw LlmException("The key saved under ${Providers.byId(t.pid).name} is actually a ${Providers.byId(owner).name} key. Open Settings → AI and paste the right key for each service.", 401)
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        // A free-chain call (timeoutMs < 120 s) gives up quickly so the next model gets a turn.
        val chained = timeoutMs < 120_000
        conn.connectTimeout = if (chained) 8_000 else 30_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        // Streaming: the read timeout is the longest wait for the next chunk, including the first.
        conn.readTimeout = if (chained && onText != null) minOf(timeoutMs, if (t.pid == "nvidia") 40_000 else 25_000) else timeoutMs
        callStart = System.currentTimeMillis(); gotFirst = false; lastFirstTokenMs = 0; lastFirstTokenAt = 0
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
                put("tools", try { ToolCalls.adapt(tools, t.pid, t.model) } catch (_: Exception) { tools })
                put("tool_choice", "auto")
            }
        }
        val who = Providers.byId(t.pid).name
        val code = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode
        } catch (e: java.net.SocketTimeoutException) {
            conn.disconnect(); throw LlmException("$who · ${t.model} didn't start answering in ${conn.readTimeout / 1000}s.", 0)
        } catch (e: java.io.IOException) {
            conn.disconnect(); throw LlmException("$who: couldn't reach the service (${e.message?.take(80)}).", 0)
        }
        if (onText == null || code !in 200..299) markFirst()
        val rh = rateHeaders(conn)
        lastCallHeaders = rh
        rh.takeIf { it.isNotEmpty() }?.let { lastRateHeaders = it }
        lastUsage = null
        if (code in 200..299 && onText != null &&
            (conn.contentType ?: "").contains("event-stream", ignoreCase = true)) {
            try { return@withContext readStream(conn, onText, onThink) }
            catch (e: LlmException) { throw LlmException("${Providers.byId(t.pid).name} · ${t.model}: ${e.message}", e.code, e.retryAfterSec) }
            catch (e: java.net.SocketTimeoutException) { throw LlmException("${Providers.byId(t.pid).name} · ${t.model} stopped sending (timed out mid-reply).", 0) }
            finally { conn.disconnect() }
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
                throw LlmException("$who: the model “${t.model}” isn't available to your key. Open Settings → Model and pick one from the list.", 404)
            if (code == 401 || code == 403)
                throw LlmException("$who rejected its key ($code). Check the $who key in Settings → AI: ${msg.take(160)}", code)
            throw LlmException("$who · ${t.model} said ($code): ${msg.take(300)}", code, retry)
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
        val calls = ArrayList<JSONObject>()
        val slotOf = HashMap<Int, Int>()
        var lastEmit = 0L
        conn.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                lastChunkAt = System.currentTimeMillis()
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = try { JSONObject(data) } catch (_: Exception) { continue }
                obj.optJSONObject("usage")?.let { lastUsage = it }
                obj.optJSONObject("error")?.let { throw LlmException("The AI service said: " + it.optString("message").take(300)) }
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val rp = listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { k ->
                    delta.optString(k, "").takeIf { delta.has(k) && !delta.isNull(k) && it.isNotEmpty() } }
                if (rp != null) { markFirst(); think.append(rp); onThink?.invoke(think.toString()) }
                val piece = delta.optString("content", "").takeIf { delta.has("content") && !delta.isNull("content") }
                if (!piece.isNullOrEmpty()) {
                    markFirst()
                    content.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 90) { onText(content.toString()); lastEmit = now }
                }
                val tc = delta.optJSONArray("tool_calls") ?: continue
                markFirst()
                for (i in 0 until tc.length()) {
                    val d = tc.getJSONObject(i)
                    // Providers disagree on "index": Gemini sends each parallel call whole, in its own
                    // chunk, often all with index 0. So a chunk that brings a NEW id, or a name for a
                    // call that already has a finished name + arguments, starts a new call instead of
                    // being glued onto the last one (that glue produced "device_statusgmail_search").
                    val idx = if (d.has("index") && !d.isNull("index")) d.optInt("index", i) else i
                    val newId = d.optString("id", "").takeIf { d.has("id") && !d.isNull("id") && it.isNotBlank() }
                    val f = d.optJSONObject("function")
                    val newName = f?.optString("name", "")?.takeIf { f.has("name") && !f.isNull("name") && it.isNotBlank() }
                    var slot = slotOf[idx]
                    val cur = slot?.let { calls[it] }
                    if (cur != null) {
                        val curId = cur.optString("id", "")
                        val curFn = cur.getJSONObject("function")
                        val idChanged = newId != null && curId.isNotBlank() && newId != curId
                        val nameRestart = newName != null && curFn.optString("name").isNotBlank() &&
                            ToolCalls.completeJson(curFn.optString("arguments"))
                        if (idChanged || nameRestart) slot = null
                    }
                    if (slot == null) {
                        slot = calls.size
                        calls.add(JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")))
                        slotOf[idx] = slot
                    }
                    val acc = calls[slot]
                    if (newId != null) acc.put("id", newId)
                    f?.let { fn ->
                        val af = acc.getJSONObject("function")
                        if (newName != null) af.put("name", af.getString("name") + newName)
                        if (fn.has("arguments") && !fn.isNull("arguments")) {
                            val a = fn.opt("arguments")
                            af.put("arguments", af.getString("arguments") + (if (a is JSONObject) a.toString() else a.toString()))
                        }
                    }
                    // Keep each call's own extra fields (Gemini 3 thought signatures must be echoed back).
                    d.optJSONObject("extra_content")?.let { acc.put("extra_content", it) }
                }
            }
        }
        if (content.isNotEmpty()) onText(content.toString())
        val msg = JSONObject().put("role", "assistant").put("content", if (content.isEmpty() && calls.isNotEmpty()) JSONObject.NULL else content.toString())
        if (think.isNotEmpty()) msg.put("_reasoning", think.toString())
        if (calls.isNotEmpty()) {
            val arr = JSONArray()
            val seen = HashSet<String>()
            calls.forEachIndexed { i, c ->
                if (c.getJSONObject("function").optString("name").isBlank()) return@forEachIndexed
                var id = c.optString("id", "").ifBlank { "call_$i" }
                if (!seen.add(id)) { id = "${id}_$i"; seen.add(id) }
                c.put("id", id); arr.put(c)
            }
            if (arr.length() > 0) msg.put("tool_calls", arr)
            else if (msg.isNull("content")) msg.put("content", "")
        }
        return msg
    }
}
