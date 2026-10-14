package com.blaze.assistant.desktop

import android.content.Context
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.terminal.Linux
import com.blaze.assistant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.25.1: a real Linux desktop (XFCE under TigerVNC) inside Blaze's Alpine Linux, started from the Desktop view's
 * one Start button or by the agent's `desktop` tool. Installs on demand ([DesktopPlan.installScript]), runs the X
 * server + session as a background job, and hands the viewer a fresh random password each start. Stop kills every
 * desktop process. If Android kills the session (low memory), the view offers Resume.
 */
object LinuxDesktop {
    data class State(val phase: Phase = Phase.IDLE, val line: String = "", val pct: Int? = null, val error: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var jobId = 0
    @Volatile var password: String = ""; private set

    private const val PREF_ON = "linux_desktop_on"

    private fun set(p: Phase, line: String, pct: Int? = null, err: String = "") { _state.value = State(p, line, pct, err) }

    fun cpuOk(): Boolean = DesktopPlan.supported(android.os.Build.SUPPORTED_ABIS.firstOrNull()) && Linux.supported()

    fun installed(): Boolean = try { Linux.installed() && File(Linux.rootfs(), DesktopPlan.INSTALLED_MARK.removePrefix("/")).exists() } catch (_: Throwable) { false }

    /** True when it was running and Android ended it (shown as a Resume card). */
    fun wasKilled(): Boolean = Settings.bool(PREF_ON, false) && _state.value.phase != Phase.RUNNING && _state.value.phase != Phase.STARTING

    fun freeMb(ctx: Context): Long = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi); mi.availMem / 1_000_000
    } catch (_: Throwable) { 1_000 }

    /** Installs if needed, then starts. Safe to call twice. Returns a plain sentence. */
    suspend fun start(ctx: Context): String = lock.withLock {
        if (!cpuOk()) { set(Phase.UNSUPPORTED, DesktopPlan.UNSUPPORTED); return DesktopPlan.UNSUPPORTED }
        if (alive()) { set(Phase.RUNNING, "Running"); return "The desktop is running." }
        if (freeMb(ctx) < DesktopPlan.MIN_FREE_MB) {
            val m = "Your phone is low on free memory right now. Close a few apps, then tap Start again."
            set(Phase.ERROR, m, err = m); return m
        }
        try {
            if (!Linux.installed()) {
                set(Phase.INSTALLING, "Setting up Linux first\u2026", 0)
                val r = Linux.install()
                if (!Linux.installed()) { set(Phase.ERROR, r, err = r); return r }
            }
            if (!installed()) {
                val r = install()
                if (r != null) { set(Phase.ERROR, r, err = r); return r }
            }
            set(Phase.STARTING, "Starting the desktop\u2026")
            password = DesktopPlan.password()
            Terminal.run(DesktopPlan.passwdScript(password), timeoutSec = 30, linux = true)
            Terminal.run(DesktopPlan.STOP_SCRIPT, timeoutSec = 20, linux = true)
            val out = Terminal.startJob(DesktopPlan.startScript(), linux = true, maxMinutes = 0)
            jobId = Regex("Started job (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            repeat(40) { if (alive()) { Settings.setBool(PREF_ON, true); set(Phase.RUNNING, "Running"); return "The desktop is running. Open the Desktop view to see it." }; delay(500) }
            val log = Terminal.run("tail -n 5 /tmp/xvnc.log 2>/dev/null", timeoutSec = 10, linux = true)
            val m = "The desktop didn't start. " + log.lines().drop(1).joinToString(" ").take(200)
            set(Phase.ERROR, m, err = m); m
        } catch (e: Exception) {
            val m = "The desktop didn't start: ${e.message ?: e.javaClass.simpleName}"
            set(Phase.ERROR, m, err = m); m
        }
    }

    /** apk add with progress; null on success, else a plain error. */
    private suspend fun install(): String? = withContext(Dispatchers.IO) {
        set(Phase.INSTALLING, "Downloading the desktop\u2026 " + DesktopPlan.sizeLine(), 0)
        val p = try {
            ProcessBuilder(Linux.command(DesktopPlan.installScript())).redirectErrorStream(true)
                .also { it.environment().putAll(Linux.env()) }.start()
        } catch (e: Exception) { return@withContext "Couldn't start the installer: ${e.message}" }
        try { p.outputStream.close() } catch (_: Exception) { }
        val tail = ArrayDeque<String>()
        p.inputStream.bufferedReader().forEachLine { line ->
            tail.addLast(line); if (tail.size > 8) tail.removeFirst()
            DesktopPlan.progressLine(line)?.let { (pct, words) -> set(Phase.INSTALLING, words, pct ?: _state.value.pct) }
        }
        val code = p.waitFor()
        if (code == 0 && installed()) null
        else "The desktop couldn't be installed (check your connection and free space). " + tail.joinToString(" ").takeLast(200)
    }

    suspend fun alive(): Boolean = try {
        Linux.installed() && Terminal.run(DesktopPlan.ALIVE_SCRIPT, timeoutSec = 10, linux = true).lines().any { it.trim() == "up" }
    } catch (_: Exception) { false }

    /** Kills every desktop process and frees its memory. */
    suspend fun stop(): String {
        set(Phase.STOPPING, "Stopping\u2026")
        Settings.setBool(PREF_ON, false)
        try { Terminal.run(DesktopPlan.STOP_SCRIPT, timeoutSec = 20, linux = true) } catch (_: Exception) { }
        if (jobId > 0) try { Terminal.stopJob(jobId) } catch (_: Exception) { }
        jobId = 0; password = ""; clickOk = false
        System.gc()
        set(Phase.STOPPED, "Stopped. Memory freed.")
        return "The desktop is stopped."
    }

    /** The viewer lost its connection: if the X server is gone, Android (or a crash) ended it. */
    fun lost() { scope.launch { if (!alive()) set(Phase.KILLED, DesktopPlan.KILLED_NOTE) } }

    // ---------- agent tool ----------
    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "desktop")
        .put("description", "A real Linux desktop (XFCE) inside Blaze's Alpine Linux, shown in the Desktop view. " +
            "start (installs on first use, ~${DesktopPlan.DOWNLOAD_MB} MB), stop, status, screenshot (attached as an image; coordinates are in that picture's pixels), " +
            "click x y [button 1 left / 3 right] [double], type text, key keys (xdotool names like ctrl+s Return), run app (terminal, files, browser, editor or a command), open (show the Desktop view).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "stop", "status", "screenshot", "click", "type", "key", "run", "open"))))
            .put("x", JSONObject().put("type", "integer")).put("y", JSONObject().put("type", "integer"))
            .put("button", JSONObject().put("type", "integer")).put("double", JSONObject().put("type", "boolean"))
            .put("text", JSONObject().put("type", "string")).put("keys", JSONObject().put("type", "string"))
            .put("app", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    /** The screenshot as a data URL (≤ 1024 px), or null. */
    suspend fun screenshot(ctx: Context): String? = withContext(Dispatchers.IO) {
        val r = Terminal.run(DesktopPlan.screenshotCmd(), timeoutSec = 30, linux = true)
        if (!r.contains("ok")) return@withContext null
        val f = File(File(ctx.filesDir, "home"), ".desktop_shot.png")
        if (!f.exists()) return@withContext null
        val b = f.readBytes(); f.delete()
        "data:image/png;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    /**
     * Runs one tool call. [confirm] asks the user (Blaze's usual approval card); start (a big download the first time),
     * type, key and run always ask; click asks unless the user already approved desktop control this session.
     */
    suspend fun run(ctx: Context, args: JSONObject, shots: MutableList<String>, confirm: suspend (String, String) -> Boolean, open: () -> Unit): String {
        val a = args.optString("action")
        if (a !in setOf("status", "stop", "open") && !cpuOk()) return DesktopPlan.UNSUPPORTED
        val needsUp = a in setOf("screenshot", "click", "type", "key", "run")
        if (needsUp && !alive()) return "The desktop isn't running. Call desktop action=start first."
        return when (a) {
            "start" -> {
                if (!installed()) {
                    if (!confirm("Install the Linux desktop?", DesktopPlan.sizeLine())) "The user said no; nothing was installed."
                    // The first install takes minutes: it runs in the background and the Desktop view shows progress.
                    else { scope.launch { start(ctx) }; open(); "Installing the desktop in the background (${DesktopPlan.sizeLine()}). Call desktop action=status in a few minutes." }
                } else start(ctx)
            }
            "stop" -> stop()
            "status" -> when {
                !cpuOk() -> DesktopPlan.UNSUPPORTED
                alive() -> "Running at ${DesktopPlan.WIDTH}x${DesktopPlan.HEIGHT}, ${DesktopPlan.DEPTH}-bit colour, 127.0.0.1 only."
                installed() -> "Installed, not running."
                else -> "Not installed (start installs it: ${DesktopPlan.sizeLine()})"
            }
            "open" -> { open(); "Opened the Desktop view." }
            "screenshot" -> screenshot(ctx)?.let { shots += it; "Desktop screenshot attached in the next message (coordinates are in its pixels; the desktop is ${DesktopPlan.WIDTH}x${DesktopPlan.HEIGHT})." }
                ?: "Couldn't take a desktop screenshot."
            "click" -> {
                val x = args.optInt("x", -1); val y = args.optInt("y", -1)
                if (x < 0 || y < 0) "click needs x and y."
                else if (!clickOk && !confirm("Let Blaze click in the Linux desktop?", "At $x, $y. You'll be asked once per session.").also { clickOk = it }) "The user said no."
                else Terminal.run(DesktopPlan.clickCmd(x, y, args.optInt("button", 1), args.optBoolean("double")), timeoutSec = 15, linux = true).let { "Clicked at $x, $y." }
            }
            "type" -> {
                val t = args.optString("text")
                if (t.isBlank()) "type needs text."
                else if (!confirm("Type in the Linux desktop?", "\u201c${t.take(120)}\u201d")) "The user said no."
                else Terminal.run(DesktopPlan.typeCmd(t), timeoutSec = 60, linux = true).let { "Typed ${t.length} characters." }
            }
            "key" -> {
                val k = args.optString("keys")
                if (k.isBlank()) "key needs keys."
                else if (!confirm("Press keys in the Linux desktop?", k)) "The user said no."
                else Terminal.run(DesktopPlan.keyCmd(k), timeoutSec = 15, linux = true).let { "Pressed $k." }
            }
            "run" -> {
                val app = args.optString("app")
                if (app.isBlank()) "run needs app."
                else if (!confirm("Open in the Linux desktop?", DesktopPlan.appCommand(app))) "The user said no."
                else Terminal.run(DesktopPlan.runCmd(app), timeoutSec = 15, linux = true).let { "Started ${DesktopPlan.appCommand(app)}. Take a screenshot to see it." }
            }
            else -> "Unknown action. Use start, stop, status, screenshot, click, type, key, run or open."
        }
    }

    @Volatile private var clickOk = false
}
