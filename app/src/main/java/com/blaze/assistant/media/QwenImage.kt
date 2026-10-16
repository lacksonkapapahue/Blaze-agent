package com.blaze.assistant.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blaze.assistant.media.QwenImageApi.Kind
import com.blaze.assistant.media.QwenImageApi.QwenFailure
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.30.8: images from Qwen's image model through the user's own chat.qwen.ai sign-in (Blaze's browser).
 * The token is read from the browser's chat.qwen.ai cookies (or its localStorage, captured when a chat.qwen.ai page
 * loads) and kept encrypted in settings. Requests go out with the browser's whole chat.qwen.ai cookie line and user
 * agent, so they look like the signed-in page. Any failure is reported as a [QwenFailure]; [Media.generate] falls
 * back to Pollinations.
 */
object QwenImage {
    private const val TAG = "BlazeQwen"
    private const val PREF_TOKEN = "conn_tok_qwen_chat"     // conn_tok_ prefix → stored encrypted (Secrets.isSecretPref)
    private const val PREF_SIGNIN_ASKED = "qwen_signin_asked"

    @Volatile private var model: String? = null
    @Volatile private var modelAt = 0L

    fun log(msg: String) {
        try { android.util.Log.w(TAG, msg) } catch (_: Throwable) { }
        try { com.blaze.assistant.device.CrashLog.write("Qwen image: $msg") } catch (_: Throwable) { }
    }

    private fun cookieLine(): String = try {
        android.webkit.CookieManager.getInstance().getCookie(QwenImageApi.BASE).orEmpty()
    } catch (_: Throwable) { "" }

    /** The signed-in token: browser cookie first (always freshest), then the saved copy. Null when signed out. */
    fun token(): String? {
        val now = System.currentTimeMillis()
        QwenImageApi.tokenFromCookies(cookieLine())?.takeIf { !QwenImageApi.expired(it, now) }?.let { t ->
            if (t != saved()) save(t)
            return t
        }
        return saved().takeIf { it.isNotBlank() && !QwenImageApi.expired(it, now) }
    }

    private fun saved(): String = try { com.blaze.assistant.agent.Settings.secret(PREF_TOKEN) } catch (_: Throwable) { "" }
    private fun save(t: String) { try { com.blaze.assistant.agent.Settings.setSecret(PREF_TOKEN, t) } catch (_: Throwable) { } }
    fun forget() = save("")

    /** BrowserEngine hands over `localStorage.getItem('token')` from each chat.qwen.ai page it finishes loading. */
    fun onPageToken(jsResult: String?) {
        val t = QwenImageApi.tokenFromJs(jsResult) ?: return
        if (!QwenImageApi.expired(t, System.currentTimeMillis()) && t != saved()) { save(t); log("token captured from chat.qwen.ai page") }
    }

    fun isQwenUrl(url: String?): Boolean = try { Uri.parse(url.orEmpty()).host?.let { it == "chat.qwen.ai" || it.endsWith(".chat.qwen.ai") } ?: false } catch (_: Throwable) { false }

    /**
     * Opens chat.qwen.ai's sign-in in Blaze's browser, at most once every 12 hours (it takes the screen).
     * Returns true when it opened it now.
     */
    fun openSignIn(ctx: Context, force: Boolean = false): Boolean {
        val s = com.blaze.assistant.agent.Settings
        val last = s.str(PREF_SIGNIN_ASKED).toLongOrNull() ?: 0L
        if (!force && System.currentTimeMillis() - last < 12 * 3600_000L) return false
        return try {
            s.setStr(PREF_SIGNIN_ASKED, System.currentTimeMillis().toString())
            ctx.startActivity(Intent(ctx, com.blaze.assistant.browser.BrowserActivity::class.java)
                .setData(Uri.parse(QwenImageApi.SIGN_IN_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            true
        } catch (e: Exception) { log("couldn't open the sign-in page: ${e.message}"); false }
    }

    /** v0.30.8: a quiet heads-up (no screen takeover), at most every 12 hours, offering the one-time Qwen sign-in. */
    fun nudgeSignIn() {
        try {
            val s = com.blaze.assistant.agent.Settings
            val last = s.str(PREF_SIGNIN_NUDGED).toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - last < 12 * 3600_000L) return
            s.setStr(PREF_SIGNIN_NUDGED, System.currentTimeMillis().toString())
            com.blaze.assistant.proactive.Proactive.say("Sign in to chat.qwen.ai once and your pictures come from Qwen. Tap to sign in.",
                "qwen_signin", urgent = false, suggest = "use qwen for images")
        } catch (e: Exception) { log("sign-in nudge failed: ${e.message}") }
    }

    private const val PREF_SIGNIN_NUDGED = "qwen_signin_nudged"

    private fun userAgent(ctx: Context): String = try { android.webkit.WebSettings.getDefaultUserAgent(ctx) } catch (_: Throwable) {
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }

    private fun open(url: String, method: String, headers: Map<String, String>, readMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000; c.readTimeout = readMs
        c.instanceFollowRedirects = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun bodyOf(c: HttpURLConnection): String = try {
        (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } catch (_: Exception) { "" }

    private fun post(c: HttpURLConnection, json: String) {
        c.doOutput = true
        c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    /** The t2i model: /api/models (public) once every 6 hours, else the last one, else the built-in default. */
    private fun modelFor(ua: String): String {
        val now = System.currentTimeMillis()
        model?.takeIf { now - modelAt < 6 * 3600_000L }?.let { return it }
        val m = try {
            val c = open("${QwenImageApi.BASE}/api/models", "GET", mapOf("Accept" to "application/json", "User-Agent" to ua, "source" to "web"), 20_000)
            val b = bodyOf(c).also { c.disconnect() }
            QwenImageApi.pickModel(b)
        } catch (_: Exception) { null }
        if (m != null) { model = m; modelAt = now }
        return m ?: model ?: QwenImageApi.DEFAULT_MODEL
    }

    /**
     * Makes one picture and returns its CDN url. [width]/[height] pick the nearest aspect ratio Qwen offers.
     * Throws [QwenFailure] (NO_TOKEN when signed out).
     */
    fun imageUrl(ctx: Context, prompt: String, width: Int, height: Int): String {
        val token = token() ?: throw QwenFailure(Kind.NO_TOKEN, "not signed in to chat.qwen.ai")
        val ua = userAgent(ctx)
        val cookie = cookieLine()
        val m = modelFor(ua)
        val now = System.currentTimeMillis()
        try {
            // 1. New chat.
            val c1 = open("${QwenImageApi.BASE}/api/v2/chats/new", "POST",
                QwenImageApi.headers(token, cookie, ua, "${QwenImageApi.BASE}/c/new-chat", java.util.UUID.randomUUID().toString(), "application/json, text/plain, */*"), 30_000)
            post(c1, QwenImageApi.newChatBody(m, now).toString())
            val code1 = c1.responseCode
            val chatId = QwenImageApi.chatIdOf(code1, c1.contentType.orEmpty(), bodyOf(c1)).also { c1.disconnect() }.getOrThrow()
            // 2. The t2i turn, streamed.
            val c2 = open(QwenImageApi.completionUrl(chatId), "POST",
                QwenImageApi.headers(token, cookie, ua, "${QwenImageApi.BASE}/c/$chatId", java.util.UUID.randomUUID().toString(), "text/event-stream, application/json, text/plain, */*"), 180_000)
            post(c2, QwenImageApi.completionBody(chatId, m, prompt, QwenImageApi.ratioFor(width, height), System.currentTimeMillis(), java.util.UUID.randomUUID().toString()).toString())
            val code2 = c2.responseCode
            val type2 = c2.contentType.orEmpty()
            if (code2 !in 200..299 || !type2.contains("event-stream", true)) {
                val b = bodyOf(c2).also { c2.disconnect() }
                return QwenImageApi.parseStream(code2, type2, b).getOrThrow()
            }
            // Read events as they come and stop at the first picture (the stream can run on with a caption).
            val all = StringBuilder()
            var fail: QwenFailure? = null
            try {
                c2.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val ev = StringBuilder()
                    while (true) {
                        val line = r.readLine() ?: break
                        if (all.length < 200_000) all.append(line).append('\n')
                        if (line.isNotBlank()) { ev.append(line).append('\n'); continue }
                        for (p in QwenImageApi.ssePayloads(ev.toString())) {
                            val (url, f) = QwenImageApi.parseEvent(p)
                            if (url != null) return url
                            if (f != null && fail == null) fail = f
                        }
                        ev.setLength(0)
                    }
                    for (p in QwenImageApi.ssePayloads(ev.toString())) QwenImageApi.parseEvent(p).first?.let { return it }
                }
            } finally { c2.disconnect() }
            throw fail ?: QwenImageApi.parseStream(200, type2, all.toString()).exceptionOrNull() as? QwenFailure
                ?: QwenFailure(Kind.FORMAT, "chat.qwen.ai sent no image link")
        } catch (e: QwenFailure) {
            if (e.kind == Kind.AUTH) forget()   // the cookie is re-read next time; a stale saved copy shouldn't linger
            throw e
        } catch (e: java.io.IOException) {
            throw QwenFailure(Kind.NETWORK, "couldn't reach chat.qwen.ai (${e.message})")
        }
    }

    /** Downloads [url] into [out]. Throws on a non-image answer. */
    fun download(url: String, out: File, ua: String) {
        val c = open(url, "GET", mapOf("User-Agent" to ua, "Referer" to QwenImageApi.BASE + "/"), 120_000)
        c.instanceFollowRedirects = true
        try {
            val code = c.responseCode
            val type = c.contentType.orEmpty()
            if (code !in 200..299) throw QwenFailure(Kind.HTTP, "image download answered $code")
            if (!type.startsWith("image") && !type.contains("octet-stream")) throw QwenFailure(Kind.FORMAT, "image download sent $type")
            c.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
        } finally { c.disconnect() }
        if (out.length() < 2000) { out.delete(); throw QwenFailure(Kind.FORMAT, "got an empty image") }
    }

    fun ua(ctx: Context) = userAgent(ctx)
}
