package com.blaze.assistant

import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.notify.Notifier
import com.blaze.assistant.voice.Voice
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.ChatScreen

class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false)
    private var leftAt = 0L

    companion object { const val EXTRA_LISTEN = "listen"; const val ACTION_CALL = "com.blaze.assistant.CALL" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.24.7: one Blaze window. "Open Blaze" from the hold-Home panel starts MainActivity through
        // startAssistantActivity, which Android puts in a separate (assistant) task even with singleTask, so two Blaze
        // cards showed in Recents. If another task already holds MainActivity, hand it this intent and close this copy.
        if (savedInstanceState == null && joinExistingTask()) return
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        locked.value = com.blaze.assistant.agent.Settings.appLock
        setContent { BlazeTheme {
            if (locked.value) com.blaze.assistant.ui.LockScreen { unlock() } else ChatScreen()
        } }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        if (savedInstanceState == null) handle(intent)
        // Long-press the Blaze icon → "Call Blaze".
        try {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            sm?.dynamicShortcuts = listOf(android.content.pm.ShortcutInfo.Builder(this, "call")
                .setShortLabel("Call Blaze").setLongLabel("Call Blaze")
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, applicationInfo.icon))
                .setIntent(android.content.Intent(this, MainActivity::class.java).setAction(ACTION_CALL)).build())
        } catch (_: Exception) { }
    }

    /** True when an older Blaze task was brought to the front and this duplicate task is being closed. */
    private fun joinExistingTask(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java) ?: return false
        val tasks = try { am.appTasks } catch (_: Exception) { return false }
        val rows = tasks.mapNotNull { t ->
            try {
                val info = t.taskInfo
                @Suppress("DEPRECATION")
                val id = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.id
                TaskDedupe.Row(id, info.baseActivity?.className ?: info.baseIntent?.component?.className)
            } catch (_: Exception) { null }
        }
        val keep = TaskDedupe.keep(taskId, rows, MainActivity::class.java.name) ?: return false
        val other = tasks.getOrNull(rows.indexOfFirst { it.id == keep }) ?: return false
        return try {
            handle(intent)
            other.moveToFront()
            finishAndRemoveTask()
            true
        } catch (_: Exception) { false }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); handle(intent)
    }

    /** Assistant button, wake word, quick tile → listen. Share sheet → ask about the shared text. */
    private fun handle(i: android.content.Intent?) {
        i ?: return
        // "Action required" notification: open the chat that's waiting on the secure form (the card shows there).
        if (i.getBooleanExtra(Notifier.EXTRA_OPEN_FORM, false)) { i.removeExtra(Notifier.EXTRA_OPEN_FORM); Agent.showFormChat(); Notifier.clearAction() }
        val listen = i.getBooleanExtra(EXTRA_LISTEN, false) ||
            i.action == android.content.Intent.ACTION_ASSIST || i.action == android.content.Intent.ACTION_VOICE_COMMAND
        if (i.action == ACTION_CALL) { i.action = null; Voice.callRequests.value = Voice.callRequests.value + 1; return }
        if (listen) {
            i.removeExtra(EXTRA_LISTEN); i.action = null
            Voice.listenRequests.value = Voice.listenRequests.value + 1
        }
        if (i.action == android.content.Intent.ACTION_SEND) {
            val shared = i.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().trim()
            @Suppress("DEPRECATION")
            val stream = i.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            i.action = null
            // Never sent on its own: another app could share instructions the user didn't write.
            // It waits in the message box until the user taps Send.
            if (stream != null) {
                // App scope: this activity may be a duplicate that closes right away (joinExistingTask).
                kotlinx.coroutines.MainScope().launch {
                    val a = com.blaze.assistant.agent.Attachments.load(applicationContext, stream)
                    Agent.sharedDraft.value = Agent.SharedDraft(shared, a)
                }
                return
            }
            if (shared.isNotEmpty()) Agent.sharedDraft.value = Agent.SharedDraft(shared)
        }
    }

    private val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Fingerprint / face / PIN. If the phone has no lock set up, Blaze stays open. */
    private fun unlock() {
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { locked.value = false; return }
        BiometricPrompt(this, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { locked.value = false }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Blaze").setAllowedAuthenticators(auth).build())
    }

    override fun onStart() {
        super.onStart()
        if (com.blaze.assistant.agent.Settings.appLock && leftAt > 0 && System.currentTimeMillis() - leftAt > 30_000) locked.value = true
        if (locked.value) window.decorView.post { unlock() }
    }

    override fun onStop() { leftAt = System.currentTimeMillis(); super.onStop() }

    override fun onResume() {
        super.onResume(); Agent.inForeground = true
        com.blaze.assistant.agent.SelfControl.activity = java.lang.ref.WeakReference(this)
        Notifier.clearReplies(); Notifier.visible(true)
        com.blaze.assistant.voice.WakeWordService.start(this)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard, and focus can land a beat after resume.
        if (hasFocus) window.decorView.postDelayed({
            lifecycleScope.launch {
                com.blaze.assistant.agent.Brains.takeFromClipboard(this@MainActivity, userTapped = false)
                    ?.let { android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_LONG).show() }
            }
        }, 600)
    }

    override fun onPause() {
        Agent.inForeground = false
        if (Agent.mood.value != com.blaze.assistant.agent.Mood.IDLE) Notifier.working(Agent.lastStep())
        Notifier.visible(false)
        super.onPause()
    }
}
