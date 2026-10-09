package com.blaze.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Brains
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.phone.BlazeAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches for a system toggle the user was sent to flip, and brings Blaze back to the front the
 * moment it's on, so nobody has to find their way back through Android settings.
 */
object ComeBack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** On Wi-Fi, quietly fetch the small Linux (≈3.5 MB) so the first beat or script starts at once. */
    fun prefetch(ctx: Context) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        if (cm.isActiveNetworkMetered) return
        scope.launch {
            try { if (!com.blaze.assistant.terminal.Linux.installed() && com.blaze.assistant.terminal.Linux.supported()) com.blaze.assistant.terminal.Linux.install() } catch (_: Throwable) { }
        }
    }
    fun watch(ctx: Context, done: () -> Boolean) {
        job?.cancel()
        val app = ctx.applicationContext
        job = scope.launch {
            val until = System.currentTimeMillis() + 180_000
            delay(1200)
            while (System.currentTimeMillis() < until) {
                if (done()) { launch(Dispatchers.Main) { Brains.bringBack(app) }; return@launch }
                delay(700)
            }
        }
    }
}

/** One phone permission in the setup checklist. */
private data class Need(val id: String, val title: String, val why: String, val ok: (Context) -> Boolean)

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private val needs = listOf(
    Need("accessibility", "Use your screen", "So Blaze can tap and type in other apps for you. It always asks before sending, paying or deleting.") { BlazeAccessibilityService.isEnabled(it) },
    Need("mic", "Hear you", "For talking to Blaze instead of typing.") { granted(it, Manifest.permission.RECORD_AUDIO) },
    Need("notifications", "Tell you when it's done", "A quick heads-up when a task finishes.") { Build.VERSION.SDK_INT < 33 || granted(it, Manifest.permission.POST_NOTIFICATIONS) },
    Need("overlay", "Show up over other apps", "So Blaze can pop up wherever you are.") { android.provider.Settings.canDrawOverlays(it) },
    Need("battery", "Keep working in the background", "Stops the phone from putting Blaze to sleep halfway through a job.") {
        it.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(it.packageName) == true },
)

/**
 * First run, three steps, nothing technical:
 *  1. Give Blaze a brain: one-tap sign-ins (or just start free)
 *  2. Let Blaze help on your phone: one button walks through every permission, coming back after each
 *  3. Say hi
 */
@Composable
fun SetupFlow(startPage: Int = 0, onDone: (firstPrompt: String?) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(startPage) }
    val brainStatus by Brains.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(900); tick++ } }
    val have = remember(tick, brainStatus) { Brains.connected() }
    val state = remember(tick) { needs.associate { it.id to it.ok(ctx) } }
    BackHandler(enabled = page > 0) { page-- }
    fun finish(prompt: String?) { Settings.setBool("onboarded", true); ComeBack.prefetch(ctx); onDone(prompt) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun fix(n: Need) {
        val pkg = Uri.parse("package:" + ctx.packageName)
        when (n.id) {
            "accessibility" -> {
                Settings.phoneControl = true
                ComeBack.watch(ctx) { BlazeAccessibilityService.isEnabled(ctx) }
                ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "mic" -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            "overlay" -> {
                ComeBack.watch(ctx) { android.provider.Settings.canDrawOverlays(ctx) }
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "battery" -> try {
                @Suppress("BatteryLife")
                ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.paper)) {
        ParticleField(com.blaze.assistant.agent.Mood.IDLE)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("0${page + 1} / 03", color = c.weak, size = 10.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.clickable { finish(null) }.padding(8.dp)) { Label("Skip", color = c.sub, size = 10.sp) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 18.dp)) {
                OrbFace(64.dp)
                Spacer(Modifier.height(22.dp))
                val (title, body) = when (page) {
                    0 -> "Give me a brain." to "I already work for free. Sign in below and I get smarter and faster. No keys, no settings: one tap and you're back here."
                    1 -> "Let me help on your phone." to "Tap the button and switch each one on. I'll bring you straight back here every time."
                    else -> "All set. Say hi." to "Ask me anything, tell me to do something on your phone, or ask me to draw a picture or make a beat."
                }
                Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, color = c.ink))
                Spacer(Modifier.height(12.dp))
                Text(body, style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, lineHeight = 24.sp, color = c.sub))
                Spacer(Modifier.height(22.dp))
                when (page) {
                    0 -> {
                        val orOn = "OpenRouter" in have.joinToString()
                        val gOn = have.any { it.startsWith("Google") }
                        SetupRow(if (orOn) "OpenRouter connected \u2713" else "Sign in with OpenRouter", "Free models, one tap. Sign in with Google or email there.", orOn) {
                            if (!busy && !orOn) { busy = true; scope.launch { Brains.connectOpenRouter(ctx); busy = false } }
                        }
                        Spacer(Modifier.height(10.dp))
                        SetupRow(if (gOn) "Google Gemini connected \u2713" else "Get a free Google brain", "Opens Google. Tap Create API key, then Copy, then come back. I'll grab it.", gOn) {
                            if (!gOn) Brains.getGoogleKey(ctx)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (brainStatus.isNotBlank()) Label(brainStatus, color = c.accent, size = 10.sp)
                        if (have.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Label("Or just continue. I'll use a free basic brain until you sign in.", color = c.weak, size = 10.sp)
                        }
                    }
                    1 -> {
                        needs.forEach { n ->
                            val on = state[n.id] == true
                            SetupRow((if (on) "\u2713  " else "") + n.title, n.why, on) { if (!on) fix(n) }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            val nextNeed = needs.firstOrNull { state[it.id] != true }
            val label = when (page) {
                0 -> if (have.isEmpty()) "Continue free" else "Continue"
                1 -> if (nextNeed != null) "Turn on: ${nextNeed.title.lowercase()}" else "Continue"
                else -> "Say hi to Blaze"
            }
            Box(Modifier.fillMaxWidth().height(56.dp).background(c.ink).clickable {
                when (page) {
                    0 -> { if (!Brains.hasAny() || Settings.providerId != Providers.AUTO && Settings.apiKey.isBlank()) Settings.providerId = Providers.AUTO; page = 1 }
                    1 -> if (nextNeed != null) fix(nextNeed) else page = 2
                    else -> finish("Hi! Introduce yourself in two sentences and tell me three useful things you can do for me.")
                }
            }, contentAlignment = Alignment.Center) { Label(label, color = c.paper, size = 11.sp) }
            if (page == 1 && nextNeed != null) Box(Modifier.fillMaxWidth().clickable { page = 2 }.padding(14.dp), contentAlignment = Alignment.Center) {
                Label("Later", color = c.sub, size = 10.sp)
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().background(if (on) c.paper else c.paper).hairline(if (on) c.accent else c.hair).clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = if (on) c.accent else c.ink))
        Spacer(Modifier.height(2.dp))
        Text(sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
    }
}

/** Small card at the top of Settings → Brain: what's connected and the one-tap buttons. */
@Composable
fun BrainCard() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Brains.status.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val have = remember(tick, st) { Brains.connected() }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Label("Brain", size = 10.sp)
        Spacer(Modifier.height(6.dp))
        Text(if (have.isEmpty()) "Free basic brain" else "Auto: " + have.joinToString(" + "),
            style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = c.ink))
        Text("Blaze picks the best free model for every message and skips busy ones by itself.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
        Spacer(Modifier.height(10.dp))
        Row {
            if ("OpenRouter" !in have.joinToString()) Box(Modifier.weight(1f).background(c.ink).clickable { scope.launch { Brains.connectOpenRouter(ctx) } }.padding(12.dp)) {
                Label("Sign in: OpenRouter", color = c.paper, size = 10.sp) }
            if ("OpenRouter" !in have.joinToString() && have.none { it.startsWith("Google") }) Spacer(Modifier.width(8.dp))
            if (have.none { it.startsWith("Google") }) Box(Modifier.weight(1f).hairline(c.ink).clickable { Brains.getGoogleKey(ctx) }.padding(12.dp)) {
                Label("Get Google key", color = c.ink, size = 10.sp) }
        }
        if (st.isNotBlank()) { Spacer(Modifier.height(6.dp)); Label(st, color = c.accent, size = 10.sp) }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
        Spacer(Modifier.height(4.dp))
        Label("Advanced: pick a provider, paste a key, choose models", color = c.weak, size = 9.sp)
    }
}
