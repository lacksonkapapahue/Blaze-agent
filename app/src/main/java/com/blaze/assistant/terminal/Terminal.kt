package com.blaze.assistant.terminal

import android.content.Context
import android.content.Intent
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real shell on the phone: Android's sh plus ~300 BusyBox tools (vi, wget, httpd, tar, grep,
 * awk, nc…) shipped inside the APK. Home is filesDir/home. Long jobs (local servers) run in the
 * background. For a full Linux (pkg install python/nodejs/gradle) Blaze hands commands to Termux.
 */
object Terminal {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)

    fun home(): File = File(app.filesDir, "home").apply { mkdirs() }
    private fun bin(): File = File(app.filesDir, "usr/bin")
    private fun busybox() = File(app.applicationInfo.nativeLibraryDir, "libbusybox.so")
    fun hasBusybox() = ::app.isInitialized && busybox().exists()

    fun init(ctx: Context) { app = ctx.applicationContext; Linux.init(ctx) }

    /** Links every BusyBox applet into usr/bin once per app version (cheap, no root). */
    @Synchronized fun setup() {
        if (!hasBusybox()) return
        val stamp = File(app.filesDir, "usr/.bb")
        val ver = try { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString() } catch (_: Exception) { "1" }
        if (stamp.exists() && stamp.readText() == ver && File(bin(), "busybox").exists()) return
        bin().deleteRecursively(); bin().mkdirs()
        val applets = try {
            ProcessBuilder(busybox().absolutePath, "--list").redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
        } catch (_: Exception) { emptyList() }
        for (a in applets + "busybox") {
            val name = a.trim(); if (name.isEmpty() || name.contains('/')) continue
            // Keep Android's own versions of a few that apps rely on.
            if (name in setOf("sh", "su", "reboot", "init", "mount", "umount")) continue
            try { Os.symlink(busybox().absolutePath, File(bin(), name).absolutePath) } catch (_: Exception) { }
        }
        stamp.parentFile?.mkdirs(); stamp.writeText(ver)
    }

    fun env(): Map<String, String> = mapOf(
        "HOME" to home().absolutePath,
        "PATH" to "${bin().absolutePath}:/system/bin:/system/xbin",
        "TMPDIR" to app.cacheDir.absolutePath,
        "TERM" to "dumb",
        "LANG" to "en_US.UTF-8",
        "PREFIX" to File(app.filesDir, "usr").absolutePath,
    )

    private fun builder(cmd: String) = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(home()).redirectErrorStream(true)
        .also { pb -> pb.environment().putAll(env()) }

    // ---------- Interactive session (Terminal screen) ----------

    private val _out = MutableStateFlow("")
    val output: StateFlow<String> = _out
    private var shell: Process? = null
    val cwdName get() = "~"

    private fun append(s: String) { val v = _out.value + s; _out.value = if (v.length > 120_000) v.takeLast(100_000) else v }

    /** "phone" = Android sh + BusyBox, "linux" = Alpine via PRoot. */
    val mode = MutableStateFlow("phone")

    fun startShell() {
        if (shell?.isAlive == true) return
        if (_out.value.isEmpty()) append(banner())
        scope.launch {
            synchronized(this@Terminal) {
                if (shell?.isAlive == true) return@launch
                try {
                    setup()
                    val linux = mode.value == "linux" && Linux.installed()
                    val pb = if (linux) ProcessBuilder(Linux.command(null)).also { it.environment().putAll(env()); it.environment().putAll(Linux.env()) }
                             else ProcessBuilder("/system/bin/sh").also { it.environment().putAll(env()) }
                    val p = pb.directory(home()).redirectErrorStream(true).start()
                    shell = p
                    append(if (linux) "[Alpine Linux — apk add <package> to install software]\n" else "[phone shell ready]\n")
                    launch {
                        val buf = CharArray(4096)
                        val r = p.inputStream.bufferedReader()
                        try { while (true) { val n = r.read(buf); if (n < 0) break; append(String(buf, 0, n)) } } catch (_: Exception) { }
                        append("\n[shell exited${try { " with code " + p.exitValue() } catch (_: Exception) { "" }}]\n")
                    }
                } catch (e: Exception) {
                    append("Couldn't start the shell: ${e.message}\n")
                }
            }
        }
    }

    private fun banner() = "Blaze terminal · ${if (mode.value == "linux") "Alpine Linux" else "sh + BusyBox"} · home is ~\n"

    fun switchMode(m: String) {
        if (mode.value == m) return
        mode.value = m
        shell?.destroy(); shell = null
        append("\n— switching to ${if (m == "linux") "Alpine Linux" else "phone shell"} —\n")
        startShell()
    }

    fun input(line: String) {
        append("$ $line\n")
        val p = shell
        if (p == null || !p.isAlive) { startShell(); scope.launch { kotlinx.coroutines.delay(1200); write(line) } } else scope.launch { write(line) }
    }
    private fun write(line: String) {
        try { shell?.outputStream?.let { it.write((line + "\n").toByteArray()); it.flush() } } catch (e: Exception) { append("error: ${e.message}\n") }
    }

    fun interrupt() { shell?.destroy(); shell = null; append("^C\n"); startShell() }
    fun clearScreen() { _out.value = "" }

    // ---------- For the agent ----------

    private val danger = Regex("(\\brm\\s+-[a-z]*r[a-z]*f?\\s+(/|~|\\*|\\.\\s*$))|\\bmkfs|\\bdd\\s+if=|\\breboot\\b|\\bsu\\b|>\\s*/dev/", RegexOption.IGNORE_CASE)
    fun risky(cmd: String) = danger.containsMatchIn(cmd)

    suspend fun run(cmd: String, timeoutSec: Int = 60, linux: Boolean = false): String = withContext(Dispatchers.IO) {
        setup()
        if (linux && !Linux.installed()) return@withContext "Linux isn't installed yet. Install it in Settings → Terminal (one-time ~3.5 MB download), or call linux_install."
        val p = try { if (linux) ProcessBuilder(Linux.command(cmd)).directory(home()).redirectErrorStream(true)
                .also { it.environment().putAll(env()); it.environment().putAll(Linux.env()) }.start() else builder(cmd).start() } catch (e: Exception) { return@withContext "Error: ${e.message}" }
        val out = StringBuilder()
        val reader = launch { try { p.inputStream.bufferedReader().forEachLine { if (out.length < 60_000) out.append(it).append('\n') } } catch (_: Exception) { } }
        val done = withTimeoutOrNull(timeoutSec.coerceIn(1, 600) * 1000L) { while (p.isAlive) kotlinx.coroutines.delay(50); true }
        if (done == null) { p.destroy(); reader.cancel(); return@withContext out.toString().takeLast(8000) + "\n[stopped after ${timeoutSec}s; use background=true for servers]" }
        reader.join()
        val code = p.exitValue()
        val text = out.toString().let { if (it.length > 8000) it.take(2000) + "\n…\n" + it.takeLast(6000) else it }
        "exit $code\n$text".trimEnd()
    }

    data class Job(val id: Int, val cmd: String, val proc: Process, val log: StringBuilder, val started: Long, val maxMs: Long = 0, var reported: Boolean = false, val pid: String = "")
    private val jobs = LinkedHashMap<Int, Job>()
    private var nextJob = 1

    fun startJob(cmd: String, linux: Boolean = false, maxMinutes: Int = 0): String {
        setup()
        if (linux && !Linux.installed()) return "Linux isn't installed yet (linux_install)."
        val p = try { if (linux) ProcessBuilder(Linux.command(cmd)).directory(home()).redirectErrorStream(true)
                .also { it.environment().putAll(env()); it.environment().putAll(Linux.env()) }.start() else builder(cmd).start() } catch (e: Exception) { return "Error: ${e.message}" }
        val jid = nextJob++
        val pid = com.blaze.assistant.agent.Processes.register("job", cmd.take(50), jid.toString(), props = org.json.JSONObject().put("command", cmd).put("max_minutes", maxMinutes))
        val j = Job(jid, cmd, p, StringBuilder(), System.currentTimeMillis(), maxMinutes * 60_000L, pid = pid)
        synchronized(jobs) { jobs[j.id] = j }
        JobKeeper.start(app)
        scope.launch { try { p.inputStream.bufferedReader().forEachLine { synchronized(j.log) { j.log.append(it).append('\n'); if (j.log.length > 50_000) j.log.delete(0, 20_000) } } } catch (_: Exception) { } }
        Thread.sleep(800)
        return "Started job ${j.id} [ID $pid]: $cmd\n" + (if (!p.isAlive) "It already exited (code ${p.exitValue()}).\n" else "") + synchronized(j.log) { j.log.toString().takeLast(1500) }
    }

    fun listJobs(): String = synchronized(jobs) {
        if (jobs.isEmpty()) "No background jobs." else jobs.values.joinToString("\n") { j ->
            "[${j.id}] ${if (j.proc.isAlive) "running" else "exited ${j.proc.exitValue()}"} · ${(System.currentTimeMillis() - j.started) / 1000}s · ${j.cmd.take(80)}\n" +
                synchronized(j.log) { j.log.toString().takeLast(400).prependIndent("    ") } }
    }

    /** Called by JobKeeper: enforce budgets and report finished jobs once. */
    fun sweep(report: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val list = synchronized(jobs) { jobs.values.toList() }
        for (j in list) {
            if (j.proc.isAlive && j.maxMs > 0 && now - j.started > j.maxMs) { j.proc.destroy(); synchronized(j.log) { j.log.append("[stopped: time budget used up]\n") } }
            if (!j.proc.isAlive && !j.reported) {
                j.reported = true
                val code = try { j.proc.exitValue() } catch (_: Exception) { -1 }
                val tail = synchronized(j.log) { j.log.toString().trim().lines().takeLast(3).joinToString(" | ") }.take(200)
                report("Job ${j.pid.ifBlank { j.id.toString() }} ${if (code == 0) "finished" else "failed (exit $code)"}: ${j.cmd.take(50)}${if (tail.isNotBlank()) "\n$tail" else ""}")
            }
        }
    }

    fun jobAlive(id: Int): Boolean = synchronized(jobs) { jobs[id]?.proc?.isAlive == true }
    fun stopJob(id: Int): String = synchronized(jobs) { jobs.remove(id)?.let { it.proc.destroy(); "Stopped job $id." } ?: "No job $id." }
    fun jobCount() = synchronized(jobs) { jobs.values.count { it.proc.isAlive } }

    // ---------- Termux bridge ----------

    fun termuxInstalled(ctx: Context) = try { ctx.packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }

    /** Needs Termux with allow-external-apps=true in ~/.termux/termux.properties, and the RUN_COMMAND permission granted to Blaze. */
    fun runInTermux(ctx: Context, cmd: String, background: Boolean): String {
        if (!termuxInstalled(ctx)) return "Termux isn't installed. Get it from F-Droid (not Play Store) for a full Linux with pkg install python, nodejs, git, openjdk, gradle."
        val i = Intent().setClassName("com.termux", "com.termux.app.RunCommandService").setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            "Sent to Termux${if (background) " (background)" else " (opens a Termux session)"}: $cmd. Output appears in Termux; for results you can read, redirect to a file under ~/storage or run it here with run_shell."
        } catch (e: SecurityException) {
            "Termux refused: grant Blaze the 'Run commands in Termux' permission (App info → Permissions → Additional) and set allow-external-apps=true in ~/.termux/termux.properties."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ---------- Tool definitions ----------

    val names = setOf("run_shell", "terminal_jobs", "terminal_stop", "termux_run", "linux_install")
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    fun tools(ctx: Context): List<JSONObject> = buildList {
        add(fn("run_shell", "Run a shell command on the phone (Android sh + BusyBox: ls, cat, grep, sed, awk, wget, curl-less http via wget, tar, unzip, vi, nc, httpd, find, ps, top, df, du...). Working dir is Blaze's home (~). Set background=true for servers, downloads, scraping or anything over ~30 s: it keeps running if Blaze is closed and the user gets a notification when it finishes or fails (optionally limit it with max_minutes). Returns exit code and output.",
            JSONObject().put("command", p("string", "Shell command")).put("timeout_seconds", p("integer", "Max seconds (default 60)")).put("background", p("boolean", "Run as a background job")).put("max_minutes", p("integer", "Time budget for a background job; 0 = none"))
            .put("linux", p("boolean", "Run inside Blaze's Alpine Linux (apk add python3/nodejs/git/openjdk17…) instead of the phone shell")), listOf("command")))
        add(fn("linux_install", "Download and set up Alpine Linux inside Blaze (one time, ~3.5 MB, no root).", JSONObject(), emptyList()))
        add(fn("terminal_jobs", "List background shell jobs with their latest output.", JSONObject(), emptyList()))
        add(fn("terminal_stop", "Stop a background shell job.", JSONObject().put("id", p("integer", "Job number")), listOf("id")))
        if (termuxInstalled(ctx)) add(fn("termux_run", "Run a command in Termux (full Linux: pkg install python nodejs git openjdk-17 gradle, build Android apps, etc.).",
            JSONObject().put("command", p("string", "bash command")).put("background", p("boolean", "Run without opening a Termux window")), listOf("command")))
    }
}
