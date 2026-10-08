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

    companion object { const val EXTRA_LISTEN = "listen" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); handle(intent)
    }

    /** Assistant button, wake word, quick tile → listen. Share sheet → ask about the shared text. */
    private fun handle(i: android.content.Intent?) {
        i ?: return
        val listen = i.getBooleanExtra(EXTRA_LISTEN, false) ||
            i.action == android.content.Intent.ACTION_ASSIST || i.action == android.content.Intent.ACTION_VOICE_COMMAND
        if (listen) {
            i.removeExtra(EXTRA_LISTEN); i.action = null
            Voice.listenRequests.value = Voice.listenRequests.value + 1
        }
        if (i.action == android.content.Intent.ACTION_SEND) {
            val shared = i.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().trim()
            @Suppress("DEPRECATION")
            val stream = i.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            i.action = null
            if (stream != null) {
                lifecycleScope.launch {
                    val a = com.blaze.assistant.agent.Attachments.load(this@MainActivity, stream)
                    if (a != null) Agent.send(shared.ifEmpty { "What's in this? Help me with it." }, attachments = listOf(a))
                }
                return
            }
            if (shared.isNotEmpty()) Agent.send("Here's something I shared with you:\n\n$shared\n\nHelp me with it (summarise or explain if unclear).")
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
        Notifier.clearReplies(); Notifier.doneWorking()
        com.blaze.assistant.voice.WakeWordService.start(this)
    }
    override fun onPause() {
        Agent.inForeground = false
        if (Agent.mood.value != com.blaze.assistant.agent.Mood.IDLE) Notifier.working(Agent.lastStep())
        super.onPause()
    }
}
