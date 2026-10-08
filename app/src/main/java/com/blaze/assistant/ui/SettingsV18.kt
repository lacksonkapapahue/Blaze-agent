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
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
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

    V18Section("Feel & accessibility")
    var card by remember { mutableStateOf(Settings.taskCard) }
    ToggleRow("Live task card", "Slim card with the current step and Stop over other apps", card) { card = !card; Settings.taskCard = card }
    var hap by remember { mutableStateOf(Settings.haptics) }
    ToggleRow("Haptic ticks", "A light tick when Blaze taps or types, a double tick when done", hap) { hap = !hap; Settings.haptics = hap }
    var scale by remember { mutableStateOf(Settings.textScale) }
    Spacer(Modifier.height(12.dp))
    Label("Chat text size", modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf(0.9f to "S", 1f to "M", 1.15f to "L", 1.3f to "XL").forEach { (v, l) ->
            Box(Modifier.weight(1f).height(40.dp).background(if (scale == v) c.ink else c.paper).hairline(c.hair).clickable { scale = v; Settings.textScale = v },
                contentAlignment = Alignment.Center) { Label(l, color = if (scale == v) c.paper else c.ink, size = 10.sp) }
        }
    }

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

// ---------------- Terminal ----------------

@Composable
fun TerminalPage() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val out by com.blaze.assistant.terminal.Terminal.output.collectAsState()
    var line by remember { mutableStateOf("") }
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(Unit) { com.blaze.assistant.terminal.Terminal.startShell() }
    LaunchedEffect(out.length) { scroll.animateScrollTo(scroll.maxValue) }
    V18Note("A real shell on your phone: Android sh plus ~300 BusyBox tools (vi, wget, tar, grep, awk, nc, httpd…). Blaze can use it too. Try: ls, df -h, httpd -f -p 8080 &")
    Box(Modifier.fillMaxWidth().height(380.dp).background(androidx.compose.ui.graphics.Color(0xFF111110)).padding(10.dp)) {
        Text(out.ifEmpty { "Starting…" }, modifier = Modifier.verticalScroll(scroll),
            style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = androidx.compose.ui.graphics.Color(0xFFF0F0EE)))
    }
    Row(Modifier.fillMaxWidth().hairline(c.ink), verticalAlignment = Alignment.CenterVertically) {
        Label("$", color = c.accent, size = 12.sp, modifier = Modifier.padding(start = 12.dp))
        Box(Modifier.weight(1f).padding(12.dp)) {
            if (line.isEmpty()) Text("command", style = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.weak))
            androidx.compose.foundation.text.BasicTextField(line, { line = it }, singleLine = true,
                textStyle = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (line.isNotBlank()) { com.blaze.assistant.terminal.Terminal.input(line); line = "" } }),
                modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.height(46.dp).width(64.dp).background(c.ink).clickable { if (line.isNotBlank()) { com.blaze.assistant.terminal.Terminal.input(line); line = "" } },
            contentAlignment = Alignment.Center) { Label("Run", color = c.paper, size = 11.sp) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        V18Button("Ctrl-C", primary = false, modifier = Modifier.weight(1f)) { com.blaze.assistant.terminal.Terminal.interrupt() }
        Spacer(Modifier.width(8.dp))
        V18Button("Clear", primary = false, modifier = Modifier.weight(1f)) { com.blaze.assistant.terminal.Terminal.clearScreen() }
    }
    V18Section("Jobs")
    var jobs by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { jobs = com.blaze.assistant.terminal.Terminal.listJobs(); delay(3000) } }
    Mono(jobs)
    var tools by remember { mutableStateOf(Settings.bool("terminal_tools", true)) }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Let Blaze use the terminal", "Risky commands (rm -rf, dd…) still ask first", tools) { tools = !tools; Settings.setBool("terminal_tools", tools) }
    V18Section("Full Linux")
    val termux = com.blaze.assistant.terminal.Terminal.termuxInstalled(ctx)
    V18Note(if (termux) "Termux is installed: Blaze can run commands there too (python, node, git, openjdk, gradle — even building Android apps). In Termux run: echo allow-external-apps=true >> ~/.termux/termux.properties, then allow Blaze under App info → Permissions → Additional permissions."
        else "For python, node, git or building Android apps on the phone, install Termux from F-Droid. Blaze can then send commands to it. Android doesn't let regular apps run downloaded programs, which is why Termux is needed for that part.")
    if (!termux) V18Button("Get Termux (F-Droid)", primary = false) {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://f-droid.org/packages/com.termux/")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------- Dashboard ----------------

@Composable
fun DashboardPage() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val running by com.blaze.assistant.dashboard.Dashboard.running.collectAsState()
    var lan by remember { mutableStateOf(com.blaze.assistant.dashboard.Dashboard.lan) }
    var auto by remember { mutableStateOf(Settings.bool("dash_auto", false)) }
    V18Note("A web page served by Blaze on your phone: chat with Blaze (it can still use your phone), see tokens and cost per day, phone health, memories, skills and jobs.")
    V18Button(if (running) "Open dashboard" else "Start & open dashboard") { com.blaze.assistant.dashboard.Dashboard.open(ctx) }
    Spacer(Modifier.height(8.dp))
    if (running) V18Button("Stop server", primary = false) { com.blaze.assistant.dashboard.Dashboard.stop() }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Share on Wi-Fi", "Open it from a laptop on the same network (link has a secret token)", lan) {
        lan = !lan; com.blaze.assistant.dashboard.Dashboard.lan = lan; if (running) com.blaze.assistant.dashboard.Dashboard.restart(ctx) }
    ToggleRow("Start with Blaze", "Keep the dashboard running in the background", auto) { auto = !auto; Settings.setBool("dash_auto", auto) }
    if (running) {
        V18Section("Links")
        Mono(com.blaze.assistant.dashboard.Dashboard.localUrl() + (if (lan) "\n" + (com.blaze.assistant.dashboard.Dashboard.lanUrl() ?: "(not on Wi-Fi)") else ""))
        if (lan) com.blaze.assistant.dashboard.Dashboard.lanUrl()?.let { u ->
            Spacer(Modifier.height(8.dp))
            V18Button("Copy Wi-Fi link", primary = false) {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Blaze dashboard", u)) }
        }
    }
}

// ---------------- Proactive ----------------

@Composable
fun ProactivePage() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val P = com.blaze.assistant.proactive.Proactive
    var on by remember { mutableStateOf(P.enabled) }
    var speak by remember { mutableStateOf(P.speak) }
    var every by remember { mutableStateOf(P.everyMin) }
    V18Note("Blaze watches what's new (notifications, calendar, phone health) and speaks up on its own when something matters: an urgent message, a meeting soon, a phone problem. Most checks stay silent. Urgent-looking messages are checked right away.")
    ToggleRow("Speak up on my own", "Heads-ups with a one-tap action", on) { on = !on; P.enabled = on }
    ToggleRow("Say it out loud", "Read heads-ups aloud when the ringer is on (urgent ones always)", speak) { speak = !speak; P.speak = speak }
    Spacer(Modifier.height(12.dp))
    Label("Check every", modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf(15, 30, 45, 90, 180).forEach { m ->
            Box(Modifier.weight(1f).height(40.dp).background(if (every == m) c.ink else c.paper).hairline(c.hair).clickable { every = m; P.everyMin = m },
                contentAlignment = Alignment.Center) { Label(if (m < 60) "${m}m" else "${m / 60}h", color = if (every == m) c.paper else c.ink, size = 10.sp) }
        }
    }
    var qf by remember { mutableStateOf(P.quietFrom) }; var qt by remember { mutableStateOf(P.quietTo) }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label("Quiet hours", modifier = Modifier.weight(1f))
        V18Button("%02d:00".format(qf), primary = false, modifier = Modifier.width(84.dp)) { qf = (qf + 1) % 24; P.quietFrom = qf }
        Label("  to  ", size = 10.sp)
        V18Button("%02d:00".format(qt), primary = false, modifier = Modifier.width(84.dp)) { qt = (qt + 1) % 24; P.quietTo = qt }
    }
    V18Note("Only urgent things get through in quiet hours. Tap a time to change it.", top = 6)
    if (!com.blaze.assistant.notify.NotificationBrain.enabled(ctx)) { Spacer(Modifier.height(6.dp)); NotificationAccessRow() }
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) != true) {
        Spacer(Modifier.height(8.dp))
        V18Note("Samsung and others put apps to sleep. Let Blaze run in the background so it can speak up and keep the wake word alive.")
        V18Button("Allow background running", primary = false) {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    V18Section("Triggers")
    val triggers by com.blaze.assistant.proactive.Triggers.all.collectAsState()
    V18Note("\u201cWhen X happens, do Y.\u201d Ask Blaze in chat, e.g. \u201cwhen Mary texts, read it to me\u201d or \u201cwhen battery drops below 20, turn on saver\u201d.")
    if (triggers.isNotEmpty()) Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        triggers.forEach { t ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { com.blaze.assistant.proactive.Triggers.toggle(t.id) }) {
                    Text(t.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (t.enabled) c.ink else c.weak))
                    Text("When ${t.event.replace('_', ' ')}${if (t.match.isNotBlank()) " · ${t.match}" else ""} → ${t.prompt}", maxLines = 2,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                Label("×", color = c.sub, size = 14.sp, modifier = Modifier.clickable { com.blaze.assistant.proactive.Triggers.remove(t.id) }.padding(8.dp))
            }
        }
    }

    V18Section("Recent heads-ups")
    val recent by P.recent.collectAsState()
    if (recent.isEmpty()) V18Note("Nothing yet.")
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        val f = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
        recent.asReversed().take(10).forEach { h ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp)) {
                Label(f.format(java.util.Date(h.time)), color = if (h.urgent) c.accent else c.weak, size = 9.sp, modifier = Modifier.width(70.dp))
                Text(h.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    V18Button("Check now", primary = false) { P.tick(force = true) }
}

// ---------------- Specialists ----------------

@Composable
fun SpecialistsPage() {
    val c = LocalBlaze.current
    var on by remember { mutableStateOf(Settings.bool("specialists_on", true)) }
    var custom by remember { mutableStateOf(com.blaze.assistant.agent.Specialists.custom()) }
    V18Note("Blaze can hand parts of a job to focused specialists, each with its own instructions and only the tools it needs. Several run at the same time, so research + writing + planning finish faster.")
    ToggleRow("Use specialists", "Blaze decides when it's worth delegating", on) { on = !on; Settings.setBool("specialists_on", on) }
    V18Section("Team")
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        (com.blaze.assistant.agent.Specialists.builtIn + custom).forEach { s ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text(s.prompt, maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    Label(s.groups.joinToString(" · "), color = c.accent, size = 9.sp)
                }
                if (s.custom) Label("×", color = c.sub, size = 14.sp, modifier = Modifier.clickable {
                    custom = custom.filterNot { it.id == s.id }; com.blaze.assistant.agent.Specialists.saveCustom(custom) }.padding(8.dp))
            }
        }
    }
    V18Section("Add your own")
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(setOf("web", "memory")) }
    V18Field("Name, e.g. Shopify helper", name) { name = it }
    V18Field("What it does and how", prompt) { prompt = it }
    Label("Tools", modifier = Modifier.padding(vertical = 8.dp))
    com.blaze.assistant.agent.Specialists.groupNames.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { g ->
                val sel = g in groups
                Box(Modifier.weight(1f).height(38.dp).background(if (sel) c.ink else c.paper).hairline(c.hair)
                    .clickable { groups = if (sel) groups - g else groups + g }, contentAlignment = Alignment.Center) {
                    Label(g, color = if (sel) c.paper else c.ink, size = 10.sp) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Spacer(Modifier.height(10.dp))
    V18Button("Add specialist") {
        if (name.isNotBlank() && prompt.isNotBlank()) {
            custom = custom + com.blaze.assistant.agent.Specialists.Spec(name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), name.trim(), prompt.trim(), groups, true)
            com.blaze.assistant.agent.Specialists.saveCustom(custom); name = ""; prompt = ""
        }
    }
}

@Composable
internal fun V18Field(hint: String, value: String, onChange: (String) -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairline(c.hair).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
        androidx.compose.foundation.text.BasicTextField(value, onChange, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}
