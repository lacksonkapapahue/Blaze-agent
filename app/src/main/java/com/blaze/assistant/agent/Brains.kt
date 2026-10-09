package com.blaze.assistant.agent

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.blaze.assistant.connect.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Give Blaze a brain" without hunting for keys:
 *  - OpenRouter: one-tap sign-in (OAuth PKCE). OpenRouter mints a key for the user and Blaze keeps it.
 *  - Google Gemini: opens the free key page; when the user copies the key and comes back, the
 *    key catcher spots it on the clipboard and saves it (nothing to paste).
 *  - Anything else keeps working keyless in the meantime.
 * Every key saved here joins the Auto chain on its own.
 */
object Brains {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** Keyed free providers the user has, by display name. */
    fun connected(): List<String> = Providers.freeOrder.filter { Settings.keyFor(it).isNotBlank() }.map { Providers.byId(it).name }
    fun hasAny(): Boolean = Providers.freeOrder.any { Settings.keyFor(it).isNotBlank() } ||
        (Settings.providerId != Providers.AUTO && Settings.apiKey.isNotBlank())

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun saveKey(pid: String, key: String) {
        Settings.setKeyFor(pid, key)
        if (!hasCustomBrain()) Settings.providerId = Providers.AUTO
        try { FreeRouter.added(pid) } catch (_: Throwable) { }
    }

    /** True when the user deliberately picked a non-Auto provider with its own key: don't override it. */
    private fun hasCustomBrain() = Settings.providerId != Providers.AUTO && Settings.apiKey.isNotBlank()

    /** One-tap OpenRouter sign-in. Returns a short human result. */
    suspend fun connectOpenRouter(ctx: Context): String = withContext(Dispatchers.IO) {
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val port = server.localPort
                val cb = "http://localhost:$port/callback"
                val url = "https://openrouter.ai/auth?" + Http.form("callback_url" to cb, "code_challenge" to challenge,
                    "code_challenge_method" to "S256", "key_label" to "Blaze on my phone")
                _status.value = "Waiting for you to sign in to OpenRouter\u2026"
                withContext(Dispatchers.Main) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                server.soTimeout = 600_000
                val code = withTimeoutOrNull(600_000) {
                    server.accept().use { sock ->
                        val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                        val q = Uri.parse("http://x" + line.split(" ").getOrElse(1) { "" })
                        val c = q.getQueryParameter("code")
                        val html = "<html><head><meta name='viewport' content='width=device-width'></head><body style='font-family:sans-serif;padding:40px'><h2>" +
                            (if (c != null) "Blaze is connected \u2713" else "Sign-in didn't finish") + "</h2><p>Going back to Blaze\u2026</p></body></html>"
                        sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
                        c
                    }
                } ?: return@withContext "Sign-in didn't finish.".also { _status.value = it }
                val body = JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256").toString()
                val (st, resp) = Http.req("POST", "https://openrouter.ai/api/v1/auth/keys", body)
                val key = if (st in 200..299) runCatching { JSONObject(resp).optString("key") }.getOrDefault("") else ""
                if (key.isBlank()) return@withContext "OpenRouter didn't hand over a key ($st). Try again.".also { _status.value = it }
                saveKey("openrouter", key)
                withContext(Dispatchers.Main) { bringBack(ctx) }
                "Connected to OpenRouter \u2713".also { _status.value = it }
            }
        } catch (e: Exception) {
            ("Couldn't connect: " + (e.message ?: e.javaClass.simpleName)).also { _status.value = it }
        }
    }

    /** Opens Google AI Studio's free key page; the key catcher saves the key when they copy it. */
    fun getGoogleKey(ctx: Context) {
        Settings.setBool("key_catch_armed", true)
        _status.value = "Tap \u201cCreate API key\u201d, then Copy, then come back. I'll take it from there."
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun bringBack(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private val patterns = listOf(
        "gemini" to Regex("^AIza[0-9A-Za-z_\\-]{30,45}$"),
        "groq" to Regex("^gsk_[0-9A-Za-z]{40,64}$"),
        "openrouter" to Regex("^sk-or-v1-[0-9a-f]{48,80}$"),
        "cerebras" to Regex("^csk-[0-9a-z]{30,64}$"),
        "nvidia" to Regex("^nvapi-[0-9A-Za-z_\\-]{40,80}$"),
    )

    /**
     * Key catcher: when Blaze comes to the front and the clipboard holds exactly one free-provider
     * key (and nothing else), it's saved for that provider. Returns a message to show, or null.
     */
    fun catchKey(ctx: Context): String? {
        if (!Settings.bool("key_catcher", true)) return null
        return try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
            if (t.isEmpty() || t.length > 120 || t.contains(' ')) return null
            val (pid, _) = patterns.firstOrNull { it.second.matches(t) } ?: return null
            if (Settings.keyFor(pid) == t) return null
            saveKey(pid, t)
            Settings.setBool("key_catch_armed", false)
            // Clear it so it doesn't linger on the clipboard.
            try { if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() } catch (_: Exception) { }
            val name = Providers.byId(pid).name
            _status.value = "Saved your $name key \u2713"
            "Got your $name key. Blaze will use it automatically."
        } catch (_: Exception) { null }
    }
}
