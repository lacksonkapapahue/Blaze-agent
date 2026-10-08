package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.blaze.assistant.agent.IntentCache
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.device.DeviceStatus
import com.blaze.assistant.device.SelfCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun V18Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
internal fun V18Button(text: String, primary: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(modifier.height(44.dp).background(if (primary) c.ink else c.paper).hairline(if (primary) c.ink else c.hair)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { Label(text, color = if (primary) c.paper else c.ink, size = 11.sp) }
}

@Composable
internal fun V18Section(title: String) { Spacer(Modifier.height(24.dp)); Label(title, modifier = Modifier.padding(bottom = 8.dp)) }

@Composable
internal fun Mono(text: String) {
    val c = LocalBlaze.current
    Text(text, modifier = Modifier.fillMaxWidth().hairline(c.hair).padding(12.dp),
        style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink))
}

/** Settings → Device & diagnostics. */
@Composable
fun DevicePage() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { status = DeviceStatus.describe(ctx); delay(5000) } }

    V18Section("Phone health")
    Mono(status.ifBlank { "Reading…" })
    V18Note("Blaze sees a one-line version of this on every request, so it knows when the battery is low or you're offline.", top = 8)

    V18Section("Self-check")
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SelfCheck.Item>>(emptyList()) }
    V18Button(if (checking) "Checking…" else "Run self-check") {
        if (!checking) { checking = true; scope.launch { results = SelfCheck.run(ctx); checking = false } }
    }
    if (results.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 10.dp).hairline(c.hair)) {
        results.forEach { r ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(7.dp).background(if (r.ok) c.accent else c.ink))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text(r.detail, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                Label(if (r.ok) "Pass" else "Fix", color = if (r.ok) c.accent else c.ink, size = 10.sp)
            }
        }
    }

    V18Section("Speed")
    var route by remember { mutableStateOf(Settings.showRoute) }
    ToggleRow("Show speed badge", "⚡ instant / on-device or ☁ model · seconds under replies", route) { route = !route; Settings.showRoute = route }
    var cached by remember { mutableStateOf(IntentCache.size()) }
    Spacer(Modifier.height(10.dp))
    V18Note("Instant commands: $cached. Commands that worked once on the phone run again with no model at all.")
    if (cached > 0) V18Button("Clear instant commands", primary = false) { IntentCache.clear(); cached = 0 }

    V18Section("Crash & lag report")
    var log by remember { mutableStateOf(CrashLog.read()) }
    V18Note(if (log.isBlank()) "Nothing recorded. Crashes, errors and very slow turns are written here, on the phone only." 
        else "${log.lines().count { it.isNotBlank() }} lines · ${CrashLog.crashes()} crash(es). Send it to whoever is fixing Blaze.")
    Row(Modifier.fillMaxWidth()) {
        V18Button("Send report", modifier = Modifier.weight(1f)) { CrashLog.shareIntent(ctx)?.let { ctx.startActivity(it) } }
        Spacer(Modifier.width(8.dp))
        V18Button("Clear", primary = false, modifier = Modifier.weight(1f)) { CrashLog.clear(); log = "" }
    }
    if (log.isNotBlank()) { Spacer(Modifier.height(10.dp)); Mono(log.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n").take(2500)) }
}
