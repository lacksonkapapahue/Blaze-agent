package com.blaze.assistant.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Blaze accounts, the same ones Blaze Desktop uses, through blaze-auth (the free Cloudflare
 * Worker in the Blaze-dekstop repo, cloud/). Email + password, or Sign in with Google / GitHub
 * in the browser (PKCE, redirect caught on a random 127.0.0.1 port, one-time code redeemed with
 * our verifier). The session token is Keystore-encrypted ("conn_tok_" prefs are secrets); the
 * signed-in user's name and email are cached for the UI.
 */
object BlazeAccount {
    const val DEFAULT_BASE = "https://blaze-auth.blaze-app.workers.dev"
    private const val SESSION = "conn_tok_blaze_session"
    private const val USER = "blaze_account_user"
    private const val CLIENT = "Blaze Android"

    data class User(val id: String, val email: String, val name: String, val avatarUrl: String,
                    val emailVerified: Boolean, val hasPassword: Boolean, val providers: List<String>) {
        fun json(): JSONObject = JSONObject().put("id", id).put("email", email).put("name", name).put("avatar_url", avatarUrl)
            .put("email_verified", emailVerified).put("has_password", hasPassword).put("providers", JSONArray(providers))
        companion object {
            fun from(o: JSONObject?): User? {
                if (o == null || o.optString("id").isBlank()) return null
                val p = o.optJSONArray("providers")
                return User(o.optString("id"), o.optString("email"), o.optString("name"), o.optString("avatar_url"),
                    o.optBoolean("email_verified"), o.optBoolean("has_password"),
                    (0 until (p?.length() ?: 0)).map { p!!.optString(it) })
            }
        }
    }

    /** What Settings › Account draws. */
    data class View(val user: User? = null, val online: Boolean = false, val login: Map<String, Boolean> = emptyMap(),
                    val connectors: Map<String, Boolean> = emptyMap(), val pending: String = "", val message: String = "") {
        val signedIn get() = user != null
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checked = 0L
    @Volatile private var signInJob: Job? = null
    @Volatile private var server: ServerSocket? = null

    /** BLAZE cloud override for local testing (Settings str "blaze_cloud_url"), else the deployed Worker. */
    fun base(): String = Settings.str("blaze_cloud_url").trim().trimEnd('/')
        .takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: DEFAULT_BASE

    fun signedIn() = _view.value.user != null
    fun user() = _view.value.user

    /** Call once at app start: shows the cached user straight away, then checks the session and providers. */
    fun start() {
        val cached = Settings.str(USER).takeIf { it.isNotBlank() }?.let { runCatching { User.from(JSONObject(it)) }.getOrNull() }
        _view.value = _view.value.copy(user = cached)
        scope.launch { refreshProviders(true); checkSession() }
    }

    // ---------------- errors ----------------

    class ApiError(val status: Int, val code: String) : Exception(code)
    class Unreachable(cause: Exception) : Exception(cause.message, cause)
    object NotConfigured : Exception("not_configured")
    object Cancelled : Exception("cancelled")

    fun message(e: Throwable): String = when (e) {
        is NotConfigured -> "That sign-in isn't switched on yet. Use your email instead."
        is Cancelled -> "Sign-in was cancelled."
        is Unreachable -> "Couldn't reach Blaze's sign-in service. Check your internet connection."
        is ApiError -> when (e.code) {
            "invalid_credentials" -> "That email and password don't match."
            "email_taken" -> "There's already a Blaze account with that email. Sign in instead."
            "invalid_email" -> "That doesn't look like an email address."
            "invalid_password" -> "Use a password of at least 8 characters."
            "rate_limited" -> "Too many tries. Wait a few minutes and try again."
            "unauthorized" -> "You're signed out. Sign in again."
            "access_denied" -> "You declined access in the browser. Nothing changed."
            else -> "Sign-in failed (${e.code})."
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------- HTTP ----------------

    private fun call(method: String, path: String, bearer: String = "", body: JSONObject? = null): JSONObject {
        val c = try { URL(base() + path).openConnection() as HttpURLConnection } catch (e: Exception) { throw Unreachable(e) }
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Blaze-Android")
            if (bearer.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val o = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code >= 400) {
                val err = o.optString("error").ifBlank { "http_$code" }
                if (err == "not_configured") throw NotConfigured
                throw ApiError(code, err)
            }
            return o
        } catch (e: java.io.IOException) {
            throw Unreachable(e)
        } finally { c.disconnect() }
    }

    private fun boolMap(o: JSONObject?): Map<String, Boolean> =
        o?.keys()?.asSequence()?.associateWith { o.optBoolean(it) } ?: emptyMap()

    /** Which sign-ins and one-click connectors the Worker has switched on (cached 10 minutes). */
    fun refreshProviders(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - checked < 600_000) return
        checked = System.currentTimeMillis()
        try {
            val p = call("GET", "/v1/providers")
            _view.value = _view.value.copy(online = true, login = boolMap(p.optJSONObject("login")), connectors = boolMap(p.optJSONObject("connectors")))
        } catch (_: Exception) {
            _view.value = _view.value.copy(online = false)
        }
    }

    /** Confirms a saved session is still good (signed out elsewhere, account deleted…). */
    fun checkSession() {
        val tok = Settings.secret(SESSION)
        if (tok.isBlank()) { if (_view.value.user != null) setSession(null, null); return }
        try {
            User.from(call("GET", "/v1/me", tok).optJSONObject("user"))?.let { setSession(tok, it) }
        } catch (e: ApiError) {
            if (e.status == 401) setSession(null, null)
        } catch (_: Exception) { /* offline: keep the cached user */ }
    }

    private fun setSession(token: String?, user: User?) {
        if (token == null || user == null) {
            Settings.setSecret(SESSION, ""); Settings.setStr(USER, "")
            _view.value = _view.value.copy(user = null)
            return
        }
        Settings.setSecret(SESSION, token); Settings.setStr(USER, user.json().toString())
        _view.value = _view.value.copy(user = user)
    }

    private fun takeSession(o: JSONObject) {
        val tok = o.optString("token"); val u = User.from(o.optJSONObject("user"))
        if (tok.isBlank() || u == null) throw ApiError(502, "no_session")
        setSession(tok, u)
    }

    // ---------------- email + password ----------------

    /** Signs in, or creates an account when [signUp]. Returns "" on success, else a plain sentence. */
    suspend fun email(signUp: Boolean, name: String, email: String, password: String): String = withContext(Dispatchers.IO) {
        val e = email.trim()
        if (e.isEmpty() || password.isEmpty()) return@withContext "Enter your email and password."
        if (signUp && password.length < 8) return@withContext "Use a password of at least 8 characters."
        try {
            val body = JSONObject().put("email", e).put("password", password).put("client", CLIENT)
            if (signUp) body.put("name", name.trim())
            takeSession(call("POST", if (signUp) "/v1/auth/signup" else "/v1/auth/login", body = body))
            ""
        } catch (x: Exception) { message(x) }
    }

    // ---------------- Sign in with Google / GitHub ----------------

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun rand(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Starts the browser sign-in. The outcome lands in [view] (user, or message). Returns "" when the browser opened. */
    fun signInWith(ctx: Context, provider: String): String {
        if (provider != "google" && provider != "github") return "Blaze can't sign in with $provider."
        val v = _view.value
        if (v.login[provider] != true) {
            return if (!v.online) "Couldn't reach Blaze's sign-in service. Check your internet connection."
            else "Sign in with ${title(provider)} isn't switched on yet. Use your email instead."
        }
        cancel()
        _view.value = _view.value.copy(pending = provider, message = "")
        val app = ctx.applicationContext
        signInJob = scope.launch {
            val msg = try {
                val r = authorize(app, "/v1/auth/$provider/start")
                if (r.optString("kind") != "session") throw ApiError(502, "no_session")
                takeSession(r)
                withContext(Dispatchers.Main) {
                    app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                        app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }
                "Signed in as " + (_view.value.user?.email ?: "")
            } catch (e: Exception) {
                if (!isActive) return@launch   // superseded or cancelled: say nothing
                message(e)
            }
            _view.value = _view.value.copy(pending = "", message = msg)
        }
        return ""
    }

    /** Stops a browser sign-in in progress. */
    fun cancel() {
        signInJob?.cancel(); signInJob = null
        runCatching { server?.close() }; server = null
        if (_view.value.pending.isNotEmpty()) _view.value = _view.value.copy(pending = "")
    }

    /**
     * Listen on 127.0.0.1:<random>, open the Worker's start URL, wait up to 10 minutes for the
     * redirect back with our state, then redeem the one-time code with the PKCE verifier.
     */
    private suspend fun authorize(ctx: Context, startPath: String, scopes: String = ""): JSONObject {
        val verifier = b64(rand(32))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(rand(24))
        val srv = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { server = it }
        try {
            srv.soTimeout = 1_000
            val redirect = "http://127.0.0.1:${srv.localPort}/cb"
            val url = base() + startPath + "?" + Http.form("state" to state, "code_challenge" to challenge,
                "code_challenge_method" to "S256", "redirect" to redirect) + (if (scopes.isNotBlank()) "&scope=" + Http.enc(scopes) else "")
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val until = System.currentTimeMillis() + 600_000
            var code: String? = null
            var err: String? = null
            while (code == null && err == null) {
                if (!kotlin.coroutines.coroutineContext.isActive || srv.isClosed) throw Cancelled
                if (System.currentTimeMillis() > until) throw Cancelled
                val sock = try { srv.accept() } catch (_: SocketTimeoutException) { continue } catch (_: java.net.SocketException) { throw Cancelled }
                sock.use { s ->
                    s.soTimeout = 5_000   // browsers open speculative connections that never send a request
                    val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                    val target = line.split(" ").getOrElse(1) { "" }
                    if (!target.startsWith("/cb")) { reply(s, 404, page(false, "Not found", "")); return@use }
                    val q = Uri.parse("http://x$target")
                    if (q.getQueryParameter("state") != state) {
                        reply(s, 200, page(false, "Sign-in didn't finish", "That link doesn't match the sign-in Blaze started. Start again from Blaze."))
                        return@use
                    }
                    val e = q.getQueryParameter("error").orEmpty()
                    val c = q.getQueryParameter("code").orEmpty()
                    when {
                        e.isNotEmpty() || c.isEmpty() -> {
                            err = e.ifEmpty { "missing_code" }
                            reply(s, 200, page(false, "Sign-in didn't finish",
                                if (err == "access_denied") "You declined access. Nothing changed; you can close this tab."
                                else "The sign-in came back with an error ($err). Go back to Blaze and try again."))
                        }
                        else -> { code = c; reply(s, 200, page(true, "You're signed in", "You can close this tab and go back to Blaze.")) }
                    }
                }
            }
            err?.let { if (it == "not_configured") throw NotConfigured else throw ApiError(400, it) }
            return call("POST", "/oauth/redeem", body = JSONObject().put("code", code).put("code_verifier", verifier).put("client", CLIENT))
        } finally {
            runCatching { srv.close() }
            if (server === srv) server = null
        }
    }

    private fun reply(s: java.net.Socket, status: Int, html: String) {
        val b = html.toByteArray()
        runCatching {
            s.getOutputStream().write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray() + b)
            s.getOutputStream().flush()
        }
    }

    private fun page(ok: Boolean, title: String, msg: String): String {
        fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val mark = if (ok) "#1F4BD8" else "#111110"
        return "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Blaze</title>" +
            "<body style=\"font:15px/1.5 system-ui,sans-serif;background:#FBFBFA;color:#111110;display:grid;place-items:center;min-height:100vh;margin:0\">" +
            "<div style=\"max-width:440px;margin:16px;padding:32px;border:1px solid rgba(17,17,16,.18);background:#fff;box-shadow:inset 3px 0 0 $mark\">" +
            "<div style=\"font:500 11px monospace;letter-spacing:.12em;text-transform:uppercase;color:$mark\">Blaze</div>" +
            "<h2 style=\"margin:8px 0 6px;font-weight:600\">${esc(title)}</h2><p style=\"margin:0;color:rgba(17,17,16,.6)\">${esc(msg)}</p></div></body>"
    }

    // ---------------- sign out / delete ----------------

    /** Ends the session here and at the service (best effort when offline). */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val tok = Settings.secret(SESSION)
        if (tok.isNotBlank()) runCatching { call("POST", "/v1/logout", tok, JSONObject()) }
        setSession(null, null)
        _view.value = _view.value.copy(message = "")
    }

    /** Deletes the Blaze account. Connected accounts and keys on this phone stay. Returns "" or a plain sentence. */
    suspend fun delete(): String = withContext(Dispatchers.IO) {
        val tok = Settings.secret(SESSION)
        if (tok.isNotBlank()) try {
            call("POST", "/v1/account/delete", tok, JSONObject().put("confirm", "delete"))
        } catch (e: ApiError) {
            if (e.status != 401) return@withContext message(e)
        } catch (e: Exception) { return@withContext message(e) }
        setSession(null, null)
        ""
    }

    fun clearMessage() { _view.value = _view.value.copy(message = "") }

    fun title(p: String) = when (p) { "github" -> "GitHub"; "google" -> "Google"; else -> p }
}
