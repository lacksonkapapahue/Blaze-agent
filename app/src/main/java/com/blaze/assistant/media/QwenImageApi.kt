package com.blaze.assistant.media

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.8: the pure half of Qwen image generation (no Android): request bodies, headers, SSE parsing, failure
 * classification, token parsing and the "use pollinations for images" phrase. [QwenImage] does the network.
 *
 * The flow mirrors what the chat.qwen.ai web app sends (as reverse-engineered by Rfym21/Qwen2API, Oct 2026, and
 * AnonymoDGH/Qwen-Reverse, Aug 2026):
 *  1. GET  /api/models                          → first model whose info.meta.chat_type has "t2i" (public, no token)
 *  2. POST /api/v2/chats/new                    {models:[m], chat_type:"t2i", chat_mode:"normal", ...} → data.id
 *  3. POST /api/v2/chat/completions?chat_id=ID  stream=true, one user message with chat_type "t2i" and size "1:1" …
 *     → SSE; the picture arrives as choices[0].delta {phase:"image_gen", content:"<url>" or "![image](<url>)"}.
 * Auth is the user's chat.qwen.ai JWT (`token` cookie / localStorage) as `Authorization: Bearer` plus `token=` cookie.
 */
object QwenImageApi {
    const val BASE = "https://chat.qwen.ai"
    const val SIGN_IN_URL = "https://chat.qwen.ai/auth?action=signin"
    /** Used when /api/models can't be read; t2i-capable on chat.qwen.ai as of Oct 2026. */
    const val DEFAULT_MODEL = "qwen3.7-plus"
    /** Header values the web front end sends (FE 0.2.81, baxia 2.5.36). */
    const val FE_VERSION = "0.2.81"
    const val BX_V = "2.5.36"

    /** Aspect ratios the t2i picker offers. */
    val RATIOS = listOf("1:1", "4:3", "3:4", "16:9", "9:16")

    enum class Kind { NO_TOKEN, AUTH, RATE_LIMIT, WAF, FORMAT, HTTP, NETWORK }

    class QwenFailure(val kind: Kind, message: String) : Exception(message)

    // ---------- Requests ----------

    /** Nearest offered aspect ratio to [width]×[height]. */
    fun ratioFor(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "1:1"
        val r = width.toDouble() / height
        return RATIOS.minByOrNull { s ->
            val (a, b) = s.split(':').map { it.toDouble() }
            Math.abs(Math.log(r / (a / b)))
        } ?: "1:1"
    }

    fun newChatBody(model: String, now: Long, chatType: String = "t2i"): JSONObject = JSONObject()
        .put("chatId", "")
        .put("title", "New Chat")
        .put("models", JSONArray().put(model))
        .put("chat_mode", "normal")
        .put("chat_type", chatType)
        .put("project_id", "")
        .put("timestamp", now)

    fun completionUrl(chatId: String) = "$BASE/api/v2/chat/completions?chat_id=" + java.net.URLEncoder.encode(chatId, "UTF-8")

    fun completionBody(chatId: String, model: String, prompt: String, size: String, now: Long, fid: String, chatType: String = "t2i"): JSONObject {
        val msg = JSONObject()
            .put("fid", fid)
            .put("parentId", JSONObject.NULL)
            .put("parent_id", JSONObject.NULL)
            .put("childrenIds", JSONArray())
            .put("role", "user")
            .put("content", prompt)
            .put("user_action", "chat")
            .put("files", JSONArray())
            .put("timestamp", now)
            .put("models", JSONArray().put(model))
            .put("chat_type", chatType)
            .put("feature_config", JSONObject().put("thinking_enabled", false).put("output_schema", "phase"))
            .put("extra", JSONObject().put("meta", JSONObject().put("subChatType", chatType)))
            .put("sub_chat_type", chatType)
        return JSONObject()
            .put("stream", true)   // stream=false is refused with Bad_Request
            .put("version", "2.1")
            .put("incremental_output", true)
            .put("chat_id", chatId)
            .put("chatId", chatId)
            .put("chat_mode", "normal")
            .put("model", model)
            .put("parent_id", JSONObject.NULL)
            .put("parentId", JSONObject.NULL)
            .put("messages", JSONArray().put(msg))
            .put("timestamp", now)
            .put("size", size)
    }

    /** Headers like the web app's. [cookie] is the WebView's whole chat.qwen.ai cookie line when we have it. */
    fun headers(token: String, cookie: String, userAgent: String, referer: String, requestId: String, accept: String = "application/json"): Map<String, String> {
        val c = if (cookie.isBlank()) "token=$token" else if (Regex("(?:^|;\\s*)token=").containsMatchIn(cookie)) cookie else "token=$token; $cookie"
        return linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to accept,
            "Authorization" to "Bearer $token",
            "Cookie" to c,
            "User-Agent" to userAgent,
            "Origin" to BASE,
            "Referer" to referer,
            "source" to "web",
            "version" to FE_VERSION,
            "bx-v" to BX_V,
            "x-request-id" to requestId,
            "timezone" to java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", java.util.Locale.US).format(java.util.Date()),
        )
    }

    // ---------- Responses ----------

    /** First model id whose meta.chat_type lists [chatType], from /api/models. */
    fun pickModel(modelsJson: String, chatType: String = "t2i"): String? = try {
        val a = JSONObject(modelsJson).optJSONArray("data") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { m ->
            val ct = m.optJSONObject("info")?.optJSONObject("meta")?.optJSONArray("chat_type")
            ct != null && (0 until ct.length()).any { ct.optString(it) == chatType }
        }?.optString("id")?.ifBlank { null }
    } catch (_: Exception) { null }

    /** chat_id from /api/v2/chats/new, or a failure explaining why not. */
    fun chatIdOf(code: Int, contentType: String, body: String): Result<String> {
        failureOf(code, contentType, body)?.let { return Result.failure(it) }
        val o = try { JSONObject(body.trim()) } catch (_: Exception) { return Result.failure(QwenFailure(Kind.FORMAT, "chats/new sent no JSON: ${body.take(80)}")) }
        val id = o.optJSONObject("data")?.optString("id").orEmpty()
        return if (o.optBoolean("success", true) && id.isNotBlank()) Result.success(id)
        else Result.failure(errorPayload(o) ?: QwenFailure(Kind.FORMAT, "chats/new answered without an id: ${body.take(120)}"))
    }

    /** A failure for a non-2xx answer, a WAF/captcha page, or an error JSON; null when [body] may hold a result. */
    fun failureOf(code: Int, contentType: String, body: String): QwenFailure? {
        val t = body.trimStart()
        if (isWaf(contentType, t)) return QwenFailure(Kind.WAF, "chat.qwen.ai showed a security check (HTTP $code)")
        when (code) {
            401, 403 -> return QwenFailure(Kind.AUTH, "chat.qwen.ai sign-in expired or refused (HTTP $code)")
            429 -> return QwenFailure(Kind.RATE_LIMIT, "chat.qwen.ai image limit reached (HTTP 429)")
        }
        if (t.startsWith("{")) try { errorPayload(JSONObject(t))?.let { return it } } catch (_: Exception) { }
        if (code !in 200..299) return QwenFailure(Kind.HTTP, "chat.qwen.ai answered HTTP $code")
        return null
    }

    fun isWaf(contentType: String, body: String): Boolean {
        val t = body.trimStart().take(4000)
        return t.startsWith("<") && (contentType.contains("html", true) || t.contains("aliyun_waf", true) || t.contains("captcha", true) || t.startsWith("<!doctype", true) || t.startsWith("<html", true))
    }

    /** {"success":false,"data":{"code":"RateLimited",...}} or {"error":{"code":...}} → failure. */
    fun errorPayload(o: JSONObject): QwenFailure? {
        val err = o.optJSONObject("error")
        val data = o.optJSONObject("data")
        val code: String; val details: String
        when {
            err != null -> { code = err.optString("code"); details = err.optString("details").ifBlank { err.optString("message") } }
            o.has("success") && !o.optBoolean("success", true) -> {
                code = data?.optString("code").orEmpty().ifBlank { o.optString("code") }
                details = data?.optString("details").orEmpty().ifBlank { o.optString("message") }
            }
            else -> return null
        }
        val all = "$code $details"
        val kind = when {
            Regex("(?i)rate.?limit|quota|too many|exceed").containsMatchIn(all) -> Kind.RATE_LIMIT
            Regex("(?i)unauthori|not.?login|login|token|auth|forbidden|401").containsMatchIn(all) -> Kind.AUTH
            else -> Kind.HTTP
        }
        val wait = data?.optInt("num", 0) ?: 0
        val msg = when (kind) {
            Kind.RATE_LIMIT -> "chat.qwen.ai image limit reached" + (if (wait > 0) " (about $wait h to wait)" else "")
            Kind.AUTH -> "chat.qwen.ai sign-in expired"
            else -> "chat.qwen.ai error ${code.ifBlank { "?" }}${if (details.isNotBlank()) ": " + details.take(120) else ""}"
        }
        return QwenFailure(kind, msg)
    }

    /** The `data:` payloads of complete SSE events in [text] (blank-line separated; `[DONE]` dropped). */
    fun ssePayloads(text: String): List<String> = text.split(Regex("\r?\n\r?\n")).mapNotNull { ev ->
        val lines = ev.split(Regex("\r?\n")).map { it.trim() }.filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
        lines.joinToString("\n").trim().takeIf { it.isNotEmpty() && it != "[DONE]" }
    }

    private val mdImg = Regex("""!\[[^\]]*]\((https?://[^\s)]+)\)""")
    private val anyUrl = Regex("""https?://[^\s<>"')\]]+""")

    /** The image url in one SSE payload, a failure it carries, or neither (null, null). */
    fun parseEvent(payload: String): Pair<String?, QwenFailure?> {
        val o = try { JSONObject(payload) } catch (_: Exception) { return null to null }
        errorPayload(o)?.let { return null to it }
        val delta = o.optJSONArray("choices")?.optJSONObject(0)?.let { it.optJSONObject("delta") ?: it.optJSONObject("message") }
            ?: return null to null
        val phase = delta.optString("phase")
        val content = delta.optString("content")
        // Regular chats that call the image tool list their pictures here.
        delta.optJSONObject("extra")?.optJSONArray("image_list")?.let { l ->
            for (i in 0 until l.length()) l.optJSONObject(i)?.optString("image")?.takeIf { it.startsWith("http") }?.let { return it to null }
        }
        if (phase == "image_gen" || phase == "image_edit") urlIn(content)?.let { return it to null }
        if (phase == "answer" || phase.isEmpty()) mdImg.find(content)?.let { return it.groupValues[1] to null }
        return null to null
    }

    fun urlIn(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        mdImg.find(t)?.let { return it.groupValues[1] }
        return anyUrl.find(t)?.value
    }

    /** Whole-stream parse (also handles a plain JSON body instead of SSE). Url, else the failure. */
    fun parseStream(code: Int, contentType: String, text: String): Result<String> {
        failureOf(code, contentType, text)?.let { return Result.failure(it) }
        val payloads = ssePayloads(text + "\n\n").ifEmpty { listOf(text.trim()) }
        var fail: QwenFailure? = null
        for (p in payloads) {
            val (url, f) = parseEvent(p)
            if (url != null) return Result.success(url)
            if (f != null && fail == null) fail = f
        }
        return Result.failure(fail ?: QwenFailure(Kind.FORMAT, "chat.qwen.ai sent no image link (${text.trim().take(120)})"))
    }

    // ---------- Token ----------

    /** The `token` cookie in a Cookie header line. */
    fun tokenFromCookies(cookie: String?): String? = cookie.orEmpty().split(';').map { it.trim() }
        .firstOrNull { it.startsWith("token=") }?.removePrefix("token=")?.trim()?.trim('"')?.takeIf { looksLikeToken(it) }

    /** evaluateJavascript result of `localStorage.getItem('token')`: a JSON string literal, "null", or raw. */
    fun tokenFromJs(result: String?): String? {
        val r = result?.trim().orEmpty()
        if (r.isEmpty() || r == "null" || r == "undefined") return null
        val v = if (r.startsWith("\"")) try { JSONArray("[$r]").optString(0) } catch (_: Exception) { r.trim('"') } else r
        return v.trim().trim('"').takeIf { looksLikeToken(it) }
    }

    fun looksLikeToken(t: String) = t.length >= 20 && !t.contains(' ') && t != "null" && t != "undefined"

    /** Seconds-since-epoch `exp` of a JWT, or null when it isn't one. */
    fun jwtExp(token: String): Long? = try {
        val parts = token.split('.')
        if (parts.size < 2) null
        else {
            val p = parts[1].replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            val json = String(java.util.Base64.getDecoder().decode(p), Charsets.UTF_8)
            JSONObject(json).optLong("exp", 0L).takeIf { it > 0 }
        }
    } catch (_: Exception) { null }

    /** True when the token is a JWT whose exp has passed (a minute of slack). Non-JWTs are never "expired". */
    fun expired(token: String, nowMs: Long): Boolean = jwtExp(token)?.let { it * 1000 < nowMs + 60_000 } ?: false

    // ---------- Route phrase ----------

    private val routeRe = Regex("""^(?:please\s+|blaze,?\s+)?(?:use|switch(?:\s+back)?\s+to|go\s+back\s+to|change\s+to|make\s+images\s+with|draw\s+with)\s+(?:the\s+)?(pollinations|qwen)(?:\s+(?:again|instead))?(?:\s+(?:for|to\s+make|to\s+draw)\s+(?:the\s+)?(?:images?|pictures?|pics|drawings?|art|image\s+generation))?(?:\s+(?:again|instead|from\s+now\s+on|please))*[\s.!]*$""", RegexOption.IGNORE_CASE)

    /** "use pollinations for images" → "pollinations"; "switch back to qwen for images" → "qwen"; else null. */
    fun routePhrase(text: String): String? {
        val t = text.trim()
        if (t.length > 90 || t.contains('\n')) return null
        val m = routeRe.find(t) ?: return null
        // "use qwen" / "use pollinations" alone mean the chat model (both are chat providers too): need an image word.
        val name = m.groupValues[1].lowercase()
        if (!Regex("(?i)\\b(images?|pictures?|pics|drawings?|art|draw)\\b").containsMatchIn(t)) return null
        return name
    }
}
