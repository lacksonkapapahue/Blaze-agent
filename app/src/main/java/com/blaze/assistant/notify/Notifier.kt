package com.blaze.assistant.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent

/** System notifications: a live "working" one with Stop, and replies you can answer inline. */
object Notifier {
    private const val CH_WORK = "blaze_work"
    private const val CH_REPLY = "blaze_reply"
    private const val ID_WORK = 1
    private const val ID_REPLY = 2
    private const val ID_WAKE = 4
    private const val ID_CONFIRM = 5
    const val KEY_REPLY = "reply_text"
    private const val ACCENT = 0xFF1F4BD8.toInt()

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_WORK, "Blaze working", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows what Blaze is doing on your phone, with a Stop button"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_REPLY, "Blaze replies", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Blaze's answer when it finishes while you're in another app"
            })
        }
    }

    private fun allowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun broadcast(action: String, mutable: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        app, action.hashCode(), Intent(app, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
    )

    /** Ongoing notification while Blaze acts. [step] is the latest step, e.g. "Opening YouTube". */
    fun working(step: String) {
        if (!allowed()) return
        val n = NotificationCompat.Builder(app, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setColor(ACCENT)
            .setContentTitle("Blaze is working")
            .setContentText(step)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp())
            .addAction(0, "Stop", broadcast(ActionReceiver.STOP))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_WORK, n) } catch (_: SecurityException) { }
    }

    fun doneWorking() = NotificationManagerCompat.from(app).cancel(ID_WORK)

    /** Blaze's reply, with an inline Reply box. */
    fun reply(text: String, isError: Boolean) {
        if (!allowed()) return
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Blaze").build()
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", broadcast(ActionReceiver.REPLY, mutable = true))
            .addRemoteInput(input).setAllowGeneratedReplies(true).build()
        val n = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setColor(ACCENT)
            .setContentTitle(if (isError) "Blaze hit a problem" else "Blaze")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(replyAction)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_REPLY, n) } catch (_: SecurityException) { }
    }

    /** Fallback when Android blocks opening Blaze from the background: a full-screen / heads-up prompt. */
    fun wakeHeard() {
        if (!allowed()) return
        val pi = PendingIntent.getActivity(app, 4,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_LISTEN, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle("I'm listening")
            .setContentText("Tap to talk to Blaze")
            .setContentIntent(pi).setFullScreenIntent(pi, true)
            .setAutoCancel(true).setTimeoutAfter(15000)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_WAKE, n) } catch (_: SecurityException) { }
    }

    fun clearReplies() = NotificationManagerCompat.from(app).cancel(ID_REPLY)

    /** Approval request with Allow / Don't buttons. */
    fun confirm(title: String, detail: String, action: String) {
        if (!allowed()) return
        val n = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp()).setAutoCancel(true)
            .addAction(0, action, broadcast(ActionReceiver.ALLOW))
            .addAction(0, "Don't", broadcast(ActionReceiver.DENY))
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_CONFIRM, n) } catch (_: SecurityException) { }
    }

    fun clearConfirm() = NotificationManagerCompat.from(app).cancel(ID_CONFIRM)
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "com.blaze.assistant.STOP"
        const val REPLY = "com.blaze.assistant.REPLY"
        const val ALLOW = "com.blaze.assistant.ALLOW"
        const val DENY = "com.blaze.assistant.DENY"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            STOP -> { Agent.stop(); Notifier.doneWorking() }
            ALLOW -> { Agent.answerConfirm(true); Notifier.clearConfirm() }
            DENY -> { Agent.answerConfirm(false); Notifier.clearConfirm() }
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                Notifier.clearReplies()
                if (!text.isNullOrBlank()) {
                    Agent.send(text)
                    Notifier.working("Thinking about: " + text.take(60))
                }
            }
        }
    }
}
