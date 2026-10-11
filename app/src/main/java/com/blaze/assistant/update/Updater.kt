package com.blaze.assistant.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.connect.Connectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-chat self-update: finds the newest Blaze-v*.apk on the repo's apk branch (private repo, so it
 * uses the GitHub connector token), downloads it with progress and installs it through
 * PackageInstaller. Prefs, keys and models live in app storage, so an update keeps them all.
 */
object Updater {
    const val TOOL = "update_blaze"
    const val CARD = "update:card"
    private const val REPO = "lacksonkapapahue/Blaze-agent"
    private const val API = "https://api.github.com/repos/$REPO/contents"

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class Downloading(val version: String, val pct: Int) : State()
        data class Installing(val version: String) : State()
        data class UpToDate(val version: String) : State()
        data class Ready(val version: String) : State()   // newer build found, not installed (install=false)
        data class NeedsPermission(val version: String) : State()
        data class Done(val version: String) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "Update the Blaze app itself to the newest build (checks, downloads and installs; settings, keys and models are kept). Use when the user asks to update Blaze or check for a new version. install=false only checks.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("install", JSONObject().put("type", "boolean").put("description", "Install if newer (default true)")))))

    /** The /update command: show the card and start. */
    fun start(ctx: Context) {
        showCard()
        scope.launch { run(ctx.applicationContext, true) }
    }

    fun showCard() = Agent.card("Blaze update", CARD)

    fun retry(ctx: Context) { scope.launch { run(ctx.applicationContext, true) } }

    fun installed(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    private fun ver(s: String): List<Int> = s.split('.', '-').mapNotNull { it.toIntOrNull() }
    private fun newer(a: String, b: String): Boolean {
        val x = ver(a); val y = ver(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun conn(url: String, token: String, raw: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            setRequestProperty("User-Agent", "Blaze-Android")
        }

    private fun fail(msg: String): String { _state.value = State.Failed(msg); return msg }

    /** Checks and (optionally) installs. Returns a plain sentence for the model. */
    suspend fun run(ctx: Context, install: Boolean): String {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return "An update is already running."
        _state.value = State.Checking
        val token = withContext(Dispatchers.IO) { try { Connectors.access("github") } catch (_: Exception) { null } }
            ?: return fail("Connect GitHub first (Settings › Connectors). The Blaze builds live in a private repo, so updates need it.")
        val have = installed(ctx)
        return try {
            val (name, version, size) = withContext(Dispatchers.IO) {
                val c = conn("$API?ref=apk", token, false)
                when (c.responseCode) {
                    401, 403 -> throw UpdateError("GitHub refused the request. Reconnect GitHub in Settings › Connectors and try again.")
                    404 -> throw UpdateError("Couldn't find the Blaze builds. The GitHub account you connected may not have access to the Blaze repo.")
                }
                if (c.responseCode !in 200..299) throw UpdateError("GitHub answered with an error (${c.responseCode}). Try again in a minute.")
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                val re = Regex("^Blaze-v(\\d+(?:\\.\\d+)*)\\.apk$")
                val best = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .mapNotNull { o -> re.find(o.optString("name"))?.let { Triple(o.optString("name"), it.groupValues[1], o.optLong("size")) } }
                    .maxWithOrNull { a, b -> if (newer(a.second, b.second)) 1 else if (newer(b.second, a.second)) -1 else 0 }
                    ?: throw UpdateError("No Blaze builds are published yet.")
                best
            }
            if (!newer(version, have)) { _state.value = State.UpToDate(have); return "Blaze is up to date (v$have)." }
            if (!install) { _state.value = State.Ready(version); return "Blaze v$version is available (installed: v$have). Say update to install it." }
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                _state.value = State.NeedsPermission(version)
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Blaze v$version is ready, but Android needs permission first: turn on 'Allow from this source' for Blaze, then tap Retry on the update card."
            }
            _state.value = State.Downloading(version, 0)
            val apk = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, name)
                val c = conn("$API/$name?ref=apk", token, true)
                if (c.responseCode !in 200..299) throw UpdateError("The download didn't start (${c.responseCode}). Check your connection and tap Retry.")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: size
                var got = 0L; var last = -1
                c.inputStream.use { inp -> f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != last) { last = pct; _state.value = State.Downloading(version, pct) }
                    }
                } }
                if (total > 0 && got < total) throw UpdateError("The download was cut off. Tap Retry.")
                f
            }
            _state.value = State.Installing(version)
            withContext(Dispatchers.IO) { installApk(ctx, apk) }
            "Downloaded Blaze v$version. Android is asking the user to confirm the install; Blaze restarts after it."
        } catch (e: UpdateError) { fail(e.message ?: "Update failed.") }
        catch (e: java.net.UnknownHostException) { fail("No internet connection. Connect and tap Retry.") }
        catch (e: java.net.SocketTimeoutException) { fail("GitHub is taking too long to answer. Tap Retry.") }
        catch (e: kotlinx.coroutines.CancellationException) { _state.value = State.Idle; throw e }
        catch (e: Exception) { fail("The update didn't work: ${e.message ?: e.javaClass.simpleName}. Tap Retry.") }
    }

    private class UpdateError(msg: String) : Exception(msg)

    private fun installApk(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("blaze.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(ctx, 7301, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val v = (_state.value as? State.Installing)?.version ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Done(v)
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Failed("The install was cancelled. Tap Retry when you're ready.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = State.Failed("Android refused this build: it isn't signed with the same key as the Blaze you have.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = State.Failed("Not enough free space to install. Free some up and tap Retry.")
            else -> _state.value = State.Failed("Android couldn't install the update" +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: ".") + " Tap Retry.")
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onResult(context, intent)
}
