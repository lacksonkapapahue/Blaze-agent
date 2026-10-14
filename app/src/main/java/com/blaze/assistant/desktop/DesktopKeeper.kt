package com.blaze.assistant.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.desktop.DesktopPlan.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v0.25.5: keeps Blaze alive while the Linux desktop downloads. In v0.25.1 the ~260 MB install ran in a plain
 * coroutine, so turning the screen off or leaving Blaze let Android freeze or kill the process mid-download (and the
 * proot/apk child with it, `--kill-on-exit`). This foreground service ("Setting up the Linux desktop", with a progress
 * bar and the same plain line as the Desktop view) holds a partial wake lock and a Wi-Fi lock until the install ends.
 */
class DesktopKeeper : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: kotlinx.coroutines.Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    companion object {
        private const val ID = 79
        private const val CHANNEL = "blaze_desktop"
        @Volatile var wanted = false; private set

        fun start(ctx: Context) {
            wanted = true
            val i = Intent(ctx, DesktopKeeper::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            wanted = false
            try { ctx.stopService(Intent(ctx, DesktopKeeper::class.java)) } catch (_: Exception) { }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun note(line: String, pct: Int?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux desktop download", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle("Setting up the Linux desktop").setContentText(line.ifBlank { "Downloading\u2026" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(line.ifBlank { "Downloading\u2026" } + "\nYou can turn the screen off; Blaze keeps going."))
            .setProgress(100, pct ?: 0, pct == null).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .also { b -> open?.let { b.setContentIntent(it) } }.build()
    }

    private fun current(): Pair<String, Int?> {
        val s = LinuxDesktop.state.value
        val linux = com.blaze.assistant.terminal.Linux
        return if (linux.installing.value) linux.status.value to s.pct else s.line to s.pct
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val (l, p) = current()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(l, p), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, note(l, p))
        } catch (_: Exception) { }
        try {
            if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blaze:desktop-download")
                .apply { setReferenceCounted(false); acquire(90 * 60_000L) }
        } catch (_: Exception) { }
        try {
            if (wifi == null) wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "blaze:desktop-download").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        if (loop?.isActive != true) loop = scope.launch {
            var last = ""
            while (true) {
                delay(1000)
                val ph = LinuxDesktop.state.value.phase
                val busy = com.blaze.assistant.terminal.Linux.installing.value || ph == Phase.INSTALLING
                if (!wanted || !busy) { stopSelf(); break }
                val (l, p) = current()
                val key = "$l|$p"
                if (key != last) { last = key; try { getSystemService(NotificationManager::class.java).notify(ID, note(l, p)) } catch (_: Exception) { } }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wake = null; wifi = null
        super.onDestroy()
    }
}
