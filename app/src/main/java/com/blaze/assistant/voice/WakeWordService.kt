package com.blaze.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.assist.BlazeInteractionService
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Always-on listener for "Hey Blaze" / "Wake up Blaze". Runs fully offline with a
 * small grammar, so nothing you say leaves the phone until the wake phrase is heard.
 */
class WakeWordService : Service() {
    companion object {
        private const val CH = "blaze_wake"
        private const val ID = 3
        private const val ACT_PAUSE = "pause"
        private const val ACT_RESUME = "resume"
        @Volatile var running = false; private set

        private const val ACT_REFRESH = "refresh"
        /** Start, or (when already running) re-promote from the foreground so Android gives the mic back. */
        fun start(ctx: Context) {
            if (!Settings.wakeWord || !WakeModel.ready(ctx)) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java).setAction(if (running) ACT_REFRESH else null)) } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }
        fun pause(ctx: Context) { if (running) send(ctx, ACT_PAUSE) }
        fun resume(ctx: Context) { if (running) send(ctx, ACT_RESUME) }
        private fun send(ctx: Context, a: String) =
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(a)) } catch (_: Exception) { null }
    }

    private var model: Model? = null
    @Volatile private var paused = false
    @Volatile private var listening = false
    private var thread: Thread? = null
    private var lastFire = 0L
    @Volatile private var pausedAt = 0L
    private val main by lazy { android.os.Handler(mainLooper) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Wake word", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Blaze listens for \"Hey Blaze\""; setShowBadge(false)
                })
        }
        if (!goForeground()) { running = false; stopSelf(); return }
        Thread {
            try {
                model = Model(WakeModel.dir(this).absolutePath)
                startListening()
            } catch (_: Exception) { stopSelf() }
        }.start()
        main.postDelayed(watchdog, 15_000)
    }

    private fun goForeground(): Boolean {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle("Listening for \"Hey Blaze\"")
            .setContentText("Offline. Turn off in Blaze settings.")
            .setOngoing(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        try {
            ServiceCompat.startForeground(this, ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) { return false }
        return true
    }

    /**
     * Self-heal: if a pause was never followed by a resume (the panel closed without listening,
     * a recogniser error, TTS cut off), start listening again on our own.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val idle = Voice.state.value == Voice.State.IDLE
            if (paused && idle && System.currentTimeMillis() - pausedAt > 12_000) { paused = false; startListening() }
            else if (!paused && !listening) startListening()
            main.postDelayed(this, 10_000)
        }
    }

    /**
     * Our own mic loop (instead of Vosk's SpeechService) so we can notice when Android hands us
     * silence: a microphone service started from the background gets an all-zero stream on
     * Android 11+. Then we ask the user to tap once to bring Blaze to the front.
     */
    @Synchronized private fun startListening() {
        val m = model ?: return
        if (paused || listening) return
        listening = true
        thread = Thread {
            var rec: Recognizer? = null
            var ar: android.media.AudioRecord? = null
            try {
                rec = Recognizer(m, 16000f, "[\"hey blaze\", \"wake up blaze\", \"okay blaze\", \"blaze\", \"[unk]\"]")
                val min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic busy")
                ar.startRecording()
                val buf = ShortArray(1600)
                var silentMs = 0L
                var warned = false
                while (listening && !paused) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) { Thread.sleep(50); continue }
                    var peak = 0
                    for (i in 0 until n) { val v = kotlin.math.abs(buf[i].toInt()); if (v > peak) peak = v }
                    if (peak == 0) { silentMs += n / 16 } else { silentMs = 0; if (warned) { warned = false; com.blaze.assistant.notify.Notifier.clearWakeBlocked() } }
                    if (silentMs > 6000 && !warned) { warned = true; com.blaze.assistant.notify.Notifier.wakeBlocked() }
                    val done = rec.acceptWaveForm(buf, n)
                    if (check(if (done) rec.result else rec.partialResult)) { rec.reset(); break }
                }
            } catch (_: Exception) {
                Thread.sleep(1500)   // mic held by another app; the watchdog retries
            } finally {
                try { ar?.stop() } catch (_: Exception) { }
                ar?.release(); rec?.close()
                listening = false
            }
        }.also { it.start() }
    }

    private fun stopListening() { listening = false; thread = null }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PAUSE -> { paused = true; pausedAt = System.currentTimeMillis(); stopListening() }
            ACT_RESUME -> { paused = false; main.postDelayed({ startListening() }, 350) }
            ACT_REFRESH -> { goForeground(); com.blaze.assistant.notify.Notifier.clearWakeBlocked()
                if (!paused) { stopListening(); main.postDelayed({ startListening() }, 300) } }
            else -> if (!paused) startListening()
        }
        return START_STICKY
    }

    /** True when the wake phrase was heard (and Blaze was woken). */
    private fun check(hyp: String?): Boolean {
        val text = try { JSONObject(hyp ?: return false).let { it.optString("text").ifEmpty { it.optString("partial") } } } catch (_: Exception) { return false }
        val heard = text.contains("hey blaze") || text.contains("wake up blaze") || text.contains("okay blaze")
        if (!heard || System.currentTimeMillis() - lastFire < 3000) return false
        lastFire = System.currentTimeMillis()
        paused = true; pausedAt = System.currentTimeMillis(); listening = false
        // Give the mic a moment to be released before the full recogniser grabs it.
        main.postDelayed({ wake() }, 250)
        return true
    }

    private fun wake() {
        // Best path: as the default assistant, open Blaze's assistant session over any app.
        if (BlazeInteractionService.showFromWake()) return
        // Otherwise the overlay panel, if Blaze may draw over apps.
        if (com.blaze.assistant.ui.Overlay.canDrawOverApps(this)) {
            android.os.Handler(mainLooper).post { com.blaze.assistant.ui.Overlay.openPanel(this, listen = true) }; return
        }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
        try { startActivity(i) } catch (_: Exception) { com.blaze.assistant.notify.Notifier.wakeHeard() }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(watchdog)
        stopListening()
        model?.close(); model = null
        super.onDestroy()
    }
}
