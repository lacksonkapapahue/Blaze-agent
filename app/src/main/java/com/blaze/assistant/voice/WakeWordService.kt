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
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Always-on listener for "Hey Blaze" / "Wake up Blaze". Runs fully offline with a
 * small grammar, so nothing you say leaves the phone until the wake phrase is heard.
 */
class WakeWordService : Service(), RecognitionListener {
    companion object {
        private const val CH = "blaze_wake"
        private const val ID = 3
        private const val ACT_PAUSE = "pause"
        private const val ACT_RESUME = "resume"
        @Volatile var running = false; private set

        fun start(ctx: Context) {
            if (!Settings.wakeWord || !WakeModel.ready(ctx)) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java)) } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }
        fun pause(ctx: Context) { if (running) send(ctx, ACT_PAUSE) }
        fun resume(ctx: Context) { if (running) send(ctx, ACT_RESUME) }
        private fun send(ctx: Context, a: String) =
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(a)) } catch (_: Exception) { null }
    }

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false
    private var lastFire = 0L

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
        } catch (e: Exception) { running = false; stopSelf(); return }
        Thread {
            try {
                model = Model(WakeModel.dir(this).absolutePath)
                startListening()
            } catch (_: Exception) { stopSelf() }
        }.start()
    }

    private fun startListening() {
        val m = model ?: return
        if (paused || speech != null) return
        try {
            val rec = Recognizer(m, 16000f, "[\"hey blaze\", \"wake up blaze\", \"okay blaze\", \"blaze\", \"[unk]\"]")
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
        } catch (_: Exception) { }
    }

    private fun stopListening() { speech?.let { it.stop(); it.shutdown() }; speech = null }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PAUSE -> { paused = true; stopListening() }
            ACT_RESUME -> { paused = false; startListening() }
        }
        return START_STICKY
    }

    private fun check(hyp: String?) {
        val text = try { JSONObject(hyp ?: return).let { it.optString("text").ifEmpty { it.optString("partial") } } } catch (_: Exception) { return }
        val heard = text.contains("hey blaze") || text.contains("wake up blaze") || text.contains("okay blaze")
        if (!heard || System.currentTimeMillis() - lastFire < 3000) return
        lastFire = System.currentTimeMillis()
        paused = true; stopListening()
        wake()
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

    override fun onPartialResult(hyp: String?) = check(hyp)
    override fun onResult(hyp: String?) = check(hyp)
    override fun onFinalResult(hyp: String?) = check(hyp)
    override fun onError(e: Exception?) { stopListening(); if (!paused) startListening() }
    override fun onTimeout() { stopListening(); if (!paused) startListening() }

    override fun onDestroy() {
        running = false
        stopListening()
        model?.close(); model = null
        super.onDestroy()
    }
}
