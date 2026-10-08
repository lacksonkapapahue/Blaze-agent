package com.blaze.assistant.routine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alarm fired, phone rebooted, or Blaze updated: run the routine / reschedule. */
class RoutineReceiver : BroadcastReceiver() {
    companion object { const val FIRE = "com.blaze.assistant.ROUTINE" }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != FIRE) { Routines.scheduleAll(); return }
        val id = intent.getIntExtra("id", -1)
        val svc = Intent(ctx, RoutineService::class.java).putExtra("id", id)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
        } catch (_: Exception) {
            // Android refused a background service: run it in the broadcast's short window instead.
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                withTimeoutOrNull(55_000) { RoutineService.runRoutine(id) }
                pending.finish()
            }
        }
    }
}

/** Keeps Blaze alive while a routine runs, then stops. */
class RoutineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        suspend fun runRoutine(id: Int) {
            val r = Routines.get(id) ?: return
            // Let anything already running finish first.
            withTimeoutOrNull(120_000) { while (Agent.mood.value != Mood.IDLE) delay(1000) }
            Agent.runRoutine(r.name, r.prompt)
            delay(500)
            withTimeoutOrNull(180_000) { while (Agent.mood.value != Mood.IDLE) delay(700) }
            Routines.ran(id)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", -1) ?: -1
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("blaze_routine", "Blaze routines", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, "blaze_routine")
            .setSmallIcon(R.drawable.ic_stat_blaze).setContentTitle("Blaze routine")
            .setContentText(Routines.get(id)?.name ?: "Running").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(77, n)
        scope.launch {
            runRoutine(id)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
