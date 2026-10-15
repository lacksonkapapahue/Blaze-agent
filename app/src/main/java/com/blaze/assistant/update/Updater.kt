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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-chat self-update: finds the newest Blaze-v*.apk on the repo's apk branch (public repo; a GitHub connection only raises the rate limit; it
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
        /** v0.28.1: [note] = a plain line while it retries or waits for a connection ("" = downloading normally). */
        data class Downloading(val version: String, val pct: Int, val note: String = "") : State()
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

    /** v0.24.4: the update runs in the background (its own scope, not the chat turn) so it can be stopped on its own. */
    @Volatile private var job: kotlinx.coroutines.Job? = null

    /** The /update command: show the (one) card and start in the background. */
    fun start(ctx: Context) {
        showCard()
        launch(ctx, true)
    }

    private fun launch(ctx: Context, install: Boolean) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch { run(app, install) }
    }

    /** One update card at a time: never a second one in the chat. */
    fun showCard() { if (!Agent.hasCard(CARD)) Agent.card("Blaze update", CARD) }

    fun retry(ctx: Context) { showCard(); launch(ctx, true) }

    /**
     * The update_blaze tool: a check answers right away; an install starts in the background and returns, so the
     * chat isn't held while it downloads (progress is on the card and in the status notification, with Stop).
     */
    suspend fun fromTool(ctx: Context, install: Boolean): String {
        if (!install) return run(ctx.applicationContext, false)
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return "An update is already running in the background."
        showCard()
        launch(ctx, true)
        return "Started updating Blaze in the background. Progress is on the update card and in the notification (with Stop); Android asks the user to confirm the install at the end."
    }

    /** Stop (from the notification or the card): cancels the download and removes the card. */
    fun stop() {
        job?.cancel(); job = null
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing || s is State.NeedsPermission) _state.value = State.Idle
        Agent.removeCard(CARD)
    }

    /** True when the card should go away by itself: the update finished (or there was nothing to do). */
    fun finished(s: State): Boolean = s is State.Done || s is State.UpToDate

    private val KEY_LAST_CHECK = "update_last_check"

    /**
     * v0.24.4 background updates: at most once a day, on launch, a quiet check. If a newer Blaze is published the
     * single update card appears with Install; nothing is downloaded until the user taps it.
     */
    fun checkInBackground(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = try { com.blaze.assistant.agent.Settings.str(KEY_LAST_CHECK, "0").toLongOrNull() ?: 0L } catch (_: Exception) { return }
        if (now - last < 24 * 3600_000L || job?.isActive == true) return
        try { com.blaze.assistant.agent.Settings.setStr(KEY_LAST_CHECK, now.toString()) } catch (_: Exception) { }
        val app = ctx.applicationContext
        job = scope.launch {
            run(app, false)
            if (_state.value is State.Ready) showCard() else _state.value = State.Idle
        }
    }

    init {
        scope.launch {
            _state.collectLatest { st ->
                val ms = ApkDownload.dismissAfterMs(st) ?: return@collectLatest
                kotlinx.coroutines.delay(ms)
                if (_state.value == st && job?.isActive != true) try { Agent.removeCard(CARD) } catch (_: Throwable) { }
            }
        }
    }

    fun installed(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Long = try {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    } catch (_: Exception) { 0L }

    /**
     * v0.8.0 renumbered Blaze for Android to 0.x to match Blaze Desktop and CLI (it was 1.17.0), so the
     * build name no longer grows. CI therefore also publishes the same APK as `Blaze-v1.999.<versionCode>.apk`:
     * older Blazes (which compare names) see it as newer and update, and this Blaze reads the build number
     * from it and compares that instead. The real name (`Blaze-v0.8.0.apk`) is what the user sees.
     */
    private val bridgeRe = Regex("^Blaze-v1\\.999\\.(\\d+)\\.apk$")

    private fun ver(s: String): List<Int> = s.split('.', '-').mapNotNull { it.toIntOrNull() }
    private fun newer(a: String, b: String): Boolean {
        val x = ver(a); val y = ver(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun conn(url: String, token: String?, raw: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            // v0.28.1: long read timeout for slow mobile data (a stalled read is retried and resumed, not fatal).
            connectTimeout = 20_000; readTimeout = 90_000
            // The repo is public: a GitHub connection only raises the rate limit, it isn't required.
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            setRequestProperty("User-Agent", "Blaze-Android")
        }

    private fun fail(msg: String): String { _state.value = State.Failed(msg); return msg }

    @Volatile private var newestCode = -1L
    private val blobShas = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Checks and (optionally) installs. Returns a plain sentence for the model. */
    suspend fun run(ctx: Context, install: Boolean): String {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return "An update is already running."
        _state.value = State.Checking
        val token = withContext(Dispatchers.IO) { try { Connectors.access("github") } catch (_: Exception) { null } }
        val have = installed(ctx)
        return try {
            val (name, version, size) = withContext(Dispatchers.IO) {
                val c = conn("$API?ref=apk", token, false)
                when (c.responseCode) {
                    401 -> throw UpdateError("GitHub refused the saved GitHub login. Reconnect GitHub in Settings › Connectors, or disconnect it, and try again.")
                    403, 429 -> throw UpdateError("GitHub is limiting update checks right now. Try again in an hour, or connect GitHub in Settings › Connectors.")
                    404 -> throw UpdateError("Couldn't find the Blaze builds. Try again later.")
                }
                if (c.responseCode !in 200..299) throw UpdateError("GitHub answered with an error (${c.responseCode}). Try again in a minute.")
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                val files = (0 until arr.length()).map { arr.getJSONObject(it) }
                val re = Regex("^Blaze-v(\\d+(?:\\.\\d+)*)\\.apk$")
                val bridge = files.mapNotNull { o -> bridgeRe.find(o.optString("name"))?.let { it.groupValues[1].toLong() to o } }.maxByOrNull { it.first }
                val named = files.filter { bridgeRe.find(it.optString("name")) == null }
                    .mapNotNull { o -> re.find(o.optString("name"))?.let { blobShas[o.optString("name")] = o.optString("sha"); Triple(o.optString("name"), it.groupValues[1], o.optLong("size")) } }
                    .maxWithOrNull { a, b -> if (newer(a.second, b.second)) 1 else if (newer(b.second, a.second)) -1 else 0 }
                newestCode = bridge?.first ?: -1L
                bridge?.let { b -> blobShas[b.second.optString("name")] = b.second.optString("sha") }
                named ?: bridge?.let { b -> Triple(b.second.optString("name"), "build ${b.first}", b.second.optLong("size")) }
                    ?: throw UpdateError("No Blaze builds are published yet.")
            }
            // Build numbers decide when CI published one (names went from 1.x to 0.x in v0.8.0); names otherwise.
            val isNewer = if (newestCode >= 0) newestCode > installedCode(ctx) else newer(version, have)
            if (!isNewer) { _state.value = State.UpToDate(have); return "Blaze is up to date (v$have)." }
            if (!install) { _state.value = State.Ready(version); return "Blaze v$version is available (installed: v$have). Say update to install it." }
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                _state.value = State.NeedsPermission(version)
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Blaze v$version is ready, but Android needs permission first: turn on 'Allow from this source' for Blaze, then tap Retry on the update card."
            }
            _state.value = State.Downloading(version, 0)
            val apk = withContext(Dispatchers.IO) {
                // v0.28.1: resumable, retried, mirrored download (ApkDownload). The partial file of this same build is kept
                // between tries and between Retry taps; anything else in the folder is cleared.
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                val f = File(dir, name)
                val part = File(dir, "$name.part")
                dir.listFiles()?.forEach { if (it.name != part.name) it.delete() }
                val me = coroutineContext[kotlinx.coroutines.Job]
                val cancelled = { me?.isActive == false }
                val sources = ApkDownload.sources(REPO, name, version.takeIf { it.firstOrNull()?.isDigit() == true }, "$API/$name?ref=apk")
                var last = -1
                try {
                    ApkDownload.fetch(sources, httpIo(ctx, token), part, f, size, blobShas[name]?.takeIf { it.length == 40 }, cancelled) { ev ->
                        when (ev) {
                            is ApkDownload.Event.Progress -> {
                                val total = if (ev.total > 0) ev.total else size
                                val pct = if (total > 0) (ev.done * 100 / total).toInt().coerceIn(0, 100) else 0
                                val cur = _state.value
                                if (pct != last || (cur is State.Downloading && cur.note.isNotEmpty())) { last = pct; _state.value = State.Downloading(version, pct) }
                            }
                            is ApkDownload.Event.Retrying -> _state.value = State.Downloading(version, last.coerceAtLeast(0), ApkDownload.retryLine(ev))
                            is ApkDownload.Event.Offline -> _state.value = State.Downloading(version, last.coerceAtLeast(0), ApkDownload.OFFLINE)
                        }
                    }
                } catch (e: ApkDownload.Cancelled) { throw kotlinx.coroutines.CancellationException("Stopped") }
                catch (e: ApkDownload.GaveUp) { com.blaze.assistant.device.CrashLog.error("Update download", e)
                    throw UpdateError("The connection kept dropping, so I stopped for now. What's downloaded is kept: tap Retry and I'll carry on from there.") }
                catch (e: ApkDownload.Damaged) { throw UpdateError("The download got damaged on the way, so I didn't install it. Tap Retry to get a fresh copy.") }
                f
            }
            // Check it before Android sees it: same app, newer build, signed by the key this Blaze trusts.
            withContext(Dispatchers.IO) { verify(ctx, apk) }?.let { apk.delete(); return fail(it) }
            _state.value = State.Installing(version)
            withContext(Dispatchers.IO) { installApk(ctx, apk) }
            "Downloaded Blaze v$version. Android is asking the user to confirm the install; Blaze restarts after it."
        } catch (e: UpdateError) { fail(e.message ?: "Update failed.") }
        catch (e: java.net.UnknownHostException) { fail("No internet connection. Connect and tap Retry.") }
        catch (e: java.net.SocketTimeoutException) { fail("GitHub is taking too long to answer. Tap Retry.") }
        catch (e: java.io.IOException) { com.blaze.assistant.device.CrashLog.error("Update", e); fail("The connection dropped while checking for the update. Tap Retry.") }
        catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = State.Idle
            try { File(ctx.cacheDir, "update").listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
            throw e
        }
        catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Update", e); fail("Something went wrong with the update. Tap Retry and I'll try again.") }
    }

    private class UpdateError(msg: String) : Exception(msg)

    /** v0.28.1: real HTTP for ApkDownload: Range from the partial file; the GitHub token only for api.github.com. */
    private fun httpIo(ctx: Context, token: String?): ApkDownload.Io = object : ApkDownload.Io {
        override fun open(url: String, from: Long): ApkDownload.Response {
            val api = url.startsWith("https://api.github.com/")
            val c = conn(url, if (api) token else null, raw = true)
            if (!api) c.setRequestProperty("Accept", "application/octet-stream")
            c.instanceFollowRedirects = true
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream else (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)))
            return ApkDownload.Response(code, c.contentLengthLong, c.getHeaderField("Content-Range"), body)
        }
        override fun online(): Boolean = try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) { true }
    }

    /** Why a downloaded APK must not be installed (null = fine). */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return "That download isn't a valid app file. Tap Retry."
        if (info.packageName != ctx.packageName) return "That download isn't Blaze, so I didn't install it."
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
        if (newCode <= oldCode) return "That download isn't newer than the Blaze you have, so I didn't install it."
        fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val trusted: Set<String>; val offered: Set<String>
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return "That download isn't signed, so I didn't install it."
            trusted = mine.signingInfo.apkContentsSigners.map { sha(it.toByteArray()) }.toSet()
            // A build signed with a newer key in Blaze's key lineage lists the old key in its history.
            offered = (si.apkContentsSigners + (si.signingCertificateHistory ?: emptyArray())).map { sha(it.toByteArray()) }.toSet()
        } else {
            trusted = mine.signatures.map { sha(it.toByteArray()) }.toSet()
            offered = (info.signatures ?: emptyArray()).map { sha(it.toByteArray()) }.toSet()
        }
        if (trusted.isEmpty() || !offered.containsAll(trusted)) return "That download isn't signed by Blaze's key, so I didn't install it."
        return null
    }

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
