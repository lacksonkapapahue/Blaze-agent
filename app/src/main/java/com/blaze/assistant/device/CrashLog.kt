package com.blaze.assistant.device

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local crash + slowness log the user can share in one tap. Nothing leaves the phone by itself. */
object CrashLog {
    private lateinit var file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "blaze_log.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write("CRASH on ${t.name}: ${android.util.Log.getStackTraceString(e).take(6000)}") } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * Added to every background scope: a failure inside Blaze's own work is logged and shown as
     * an error in the chat instead of closing the app.
     */
    val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        write("CAUGHT: ${android.util.Log.getStackTraceString(e).take(4000)}")
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { com.blaze.assistant.agent.Agent.reportError("Something went wrong (${e.javaClass.simpleName}: ${e.message?.take(120)}). It's in the crash report; try again.") } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    private val recent = ArrayDeque<Long>()

    /**
     * Keep the app alive through a stray error on the main thread (a tap handler, a UI update):
     * log it, show it in chat, and carry on. If errors pile up (5 in 10 s), let Android restart us.
     */
    fun shieldMainThread() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    val now = System.currentTimeMillis()
                    recent.addLast(now); while (recent.isNotEmpty() && now - recent.first() > 10_000) recent.removeFirst()
                    write("CAUGHT on main: ${android.util.Log.getStackTraceString(e).take(4000)}")
                    if (recent.size >= 5 || e is OutOfMemoryError) throw e
                    try { com.blaze.assistant.agent.Agent.reportError("Something went wrong (${e.javaClass.simpleName}: ${e.message?.take(120)}). Blaze kept running; it's in the crash report.") } catch (_: Throwable) { }
                }
            }
        }
    }

    @Synchronized fun write(line: String) {
        if (!::file.isInitialized) return
        try {
            if (file.length() > 400_000) file.writeText(file.readText().takeLast(200_000))
            file.appendText("${fmt.format(Date())}  $line\n")
        } catch (_: Exception) { }
    }

    /** Log steps that take long so we can see where time goes. */
    fun slow(what: String, ms: Long) { if (ms >= 8000) write("SLOW ${ms} ms: $what") }
    fun error(what: String, e: Throwable?) = write("ERROR $what: ${e?.javaClass?.simpleName}: ${e?.message}")

    fun read(): String = try { if (::file.isInitialized && file.exists()) file.readText() else "" } catch (_: Exception) { "" }
    fun crashes(): Int = read().lines().count { it.contains("CRASH") }
    fun clear() { try { file.delete() } catch (_: Exception) { } }

    fun shareIntent(ctx: Context): Intent? {
        if (!::file.isInitialized || !file.exists()) return null
        val out = File(ctx.cacheDir, "blaze-report.txt")
        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        out.writeText("Blaze ${pi?.versionName} (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n\n" + read().takeLast(150_000))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Blaze report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
