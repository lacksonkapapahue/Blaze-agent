package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.25.0 "Blaze Cloud": every model call goes through the Blaze gateway (one OpenAI-compatible endpoint,
 * signed in with the Blaze account). The gateway holds the provider keys and picks the model per task, so
 * the phone never chooses a model and never uses a key of its own. This object is the pure part (tested):
 * which task hint a request carries, which failures are worth a quiet retry, how long to wait, and the one
 * short line a person sees when every retry failed.
 */
object Cloud {
    /** The gateway's "pick for me" model name; the task hint below does the real choosing. */
    const val MODEL = "blaze-auto"
    val TASKS = listOf("chat", "quick", "code", "vision", "long", "agent")
    /** About 12k tokens, the gateway's own "long" cut-off (gateway/src/router.ts LONG_CHARS). */
    const val LONG_CHARS = 48_000
    /** Tries per request (the first + two quiet retries). */
    const val MAX_TRIES = 3

    const val SIGNED_OUT = "Sign in to your Blaze account and I'm ready to go."
    const val EXPIRED = "Your Blaze sign-in has expired. Sign in again and I'll carry on."
    const val UNREACHABLE = "I couldn't reach Blaze Cloud just now. Check your data or Wi-Fi, then tap Retry."
    const val BUSY = "Blaze Cloud is busy right now. Tap Retry in a moment."

    private val codeCue = Regex("""(```|\b(code|coding|function|bug|stack ?trace|compile|kotlin|python|javascript|typescript|java|rust|golang|sql|regex|script|refactor|debug)\b)""", RegexOption.IGNORE_CASE)

    private fun hasImage(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val c = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) if (c.optJSONObject(j)?.optString("type") == "image_url") return true
        }
        return false
    }

    private fun lastUserText(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            return when (val c = m.opt("content")) {
                is String -> c
                is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text") }.joinToString(" ")
                else -> ""
            }
        }
        return ""
    }

    /** The gateway task for one request: vision > long > agent (tools) > quick (short chat) > code > chat. */
    fun task(messages: JSONArray, tools: JSONArray?, lite: Boolean = false): String = when {
        hasImage(messages) -> "vision"
        messages.toString().length > LONG_CHARS -> "long"
        tools != null && tools.length() > 0 -> "agent"
        codeCue.containsMatchIn(lastUserText(messages)) -> "code"
        lite -> "quick"
        else -> "chat"
    }

    /** Adds the task hint the gateway reads (`blaze_task`; it strips it before calling a provider). */
    fun tag(body: JSONObject, task: String): JSONObject = body.put("model", MODEL).put("blaze_task", task)

    /**
     * Worth a quiet retry: no answer (network, timeout: code 0), busy (429 other than the daily allowance),
     * the gateway's side (5xx), or a model that went away / was refused (404, "model not found"): the
     * gateway routes to its task lineup again on the next try.
     */
    fun retryable(code: Int, text: String): Boolean {
        val t = text.lowercase()
        if (code == 401 || code == 403) return false
        if (code == 429 && ("allowance" in t || "quota_exceeded" in t)) return false
        return code == 0 || code == 408 || code == 429 || code in 500..599 || code == 404 || gone(t)
    }

    fun gone(t: String): Boolean = listOf("not found", "model_not_found", "no longer available", "decommissioned", "unknown model",
        "invalid model", "doesn't offer that model", "isn't available", "paid_model", "paid-only").any { it in t.lowercase() }

    /** Before try [attempt] + 1 (0-based): 1 s, then 3 s; a server's Retry-After wins when it is under 20 s. */
    fun backoffMs(attempt: Int, retryAfterSec: Int = 0): Long {
        if (retryAfterSec in 1..20) return retryAfterSec * 1000L
        return when (attempt) { 0 -> 1_000L; 1 -> 3_000L; else -> 7_000L }
    }

    /** The one line a person sees when every try failed: no model names, no settings. */
    fun friendly(code: Int, text: String): String {
        val t = text.lowercase()
        return when {
            code == 401 || code == 403 -> EXPIRED
            code == 429 && ("allowance" in t || "quota_exceeded" in t) -> "You've used today's free messages. They come back tomorrow."
            code == 429 || code in 500..599 || gone(t) || code == 404 -> BUSY
            else -> UNREACHABLE
        }
    }

    // ---------------- v0.26.2: the retry loop, empty replies included ----------------

    /** One try: stream it or not, with the "answer now" nudge or not, and the routes ("provider/model") to skip. */
    data class Attempt(val n: Int, val stream: Boolean, val nudge: Boolean, val avoid: List<String>)

    /**
     * Every request's tries (LlmClient.completeRaw). Errors as before: a request too big is trimmed once
     * ([trim] → true), 401/403 is the expired sign-in, other non-retryable errors surface, retryable ones back off
     * up to [MAX_TRIES]; a stream that broke mid-reply (code 0) also skips the route that served it next time.
     * New: an EMPTY reply (reasoning only, or nothing; [EmptyReply.isEmpty]) is a failed try too, never an answer.
     * After the first, the same route is asked again without streaming and with the nudge; after the second,
     * the routes that came back empty are skipped (the gateway's next model); the third fails with
     * [EmptyReply.FAILED]. [served] = the route of the last call ("provider/model", "" if unknown).
     */
    suspend fun drive(streaming: Boolean, call: suspend (Attempt) -> JSONObject, served: () -> String,
                      trim: (LlmClient.LlmException) -> Boolean = { false }, sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
                      onEmpty: (Int) -> Unit = {}): JSONObject {
        val avoid = LinkedHashSet<String>()
        var stream = streaming; var nudge = false
        var errors = 0; var empties = 0; var n = 0
        var last: LlmClient.LlmException? = null
        while (errors < MAX_TRIES && empties < EmptyReply.TRIES && n < MAX_TRIES + EmptyReply.TRIES + 1) {
            val a = Attempt(n++, stream, nudge, avoid.toList())
            try {
                val m = call(a)
                if (!EmptyReply.isEmpty(m)) return m
                empties++
                onEmpty(empties)
                if (empties == 1) { stream = false; nudge = true }
                else { served().takeIf { it.isNotBlank() }?.let { avoid += it }; stream = streaming; nudge = true }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: LlmClient.LlmException) {
                if (trim(e)) continue
                if (e.code == 401 || e.code == 403) throw LlmClient.LlmException(EXPIRED, e.code)
                if (!retryable(e.code, e.message.orEmpty())) {
                    if (ToolCalls.isShapeError(e)) throw e
                    throw LlmClient.LlmException(friendly(e.code, e.message.orEmpty()), e.code, e.retryAfterSec)
                }
                last = e
                errors++
                // A stream that stalled or broke after it started (reasoning only, then a timeout): next model.
                if (e.code == 0) served().takeIf { it.isNotBlank() }?.let { avoid += it }
                if (errors < MAX_TRIES) sleep(backoffMs(errors - 1, e.retryAfterSec))
            }
        }
        if (empties >= EmptyReply.TRIES || last == null) throw LlmClient.LlmException(EmptyReply.FAILED, EmptyReply.CODE)
        throw LlmClient.LlmException(friendly(last.code, last.message.orEmpty()), last.code, last.retryAfterSec)
    }
}
