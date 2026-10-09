package com.blaze.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.blaze.assistant.assist.BlazeInteractionService
import com.blaze.assistant.voice.WakeModel
import com.blaze.assistant.voice.WakeWordService
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.ModelCatalog
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Quota
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope

/** Settings, grouped into pages. [page] null = the category list. */
data class SettingsPage(val id: String, val title: String, val sub: String)

/** The four that matter, shown first; the rest sit under Advanced. */
val essentialPages = setOf("ai", "persona", "control", "voice", "feel")

val settingsPages = listOf(
    SettingsPage("ai", "AI & model", "Provider, key, model, reasoning, follow-ups"),
    SettingsPage("feel", "Look & feel", "Tap highlights, sounds, compact panel"),
    SettingsPage("connections", "Connections", "Google, GitHub, Spotify, Notion, Slack, MCP"),
    SettingsPage("proactive", "Proactive & triggers", "Speak up on its own, when-X-do-Y"),
    SettingsPage("specialists", "Specialists", "Sub-agents Blaze can delegate to"),
    SettingsPage("terminal", "Terminal", "A real shell, jobs, Termux"),
    SettingsPage("dashboard", "Dashboard", "Local web dashboard and analytics"),
    SettingsPage("persona", "Personality", "How Blaze talks"),
    SettingsPage("control", "Phone control", "Accessibility, skills, permissions"),
    SettingsPage("commands", "Slash commands", "Your own /commands and prompt skills"),
    SettingsPage("voice", "Voice & wake word", "Hey Blaze, spoken replies, default assistant"),
    SettingsPage("orb", "Floating orb", "Keep Blaze over other apps"),
    SettingsPage("routines", "Routines", "Things Blaze does on a schedule"),
    SettingsPage("memory", "Memory & learning", "What Blaze remembers and has learned"),
    SettingsPage("usage", "Usage", "Tokens and estimated cost"),
    SettingsPage("privacy", "Privacy & safety", "App lock, approvals"),
    SettingsPage("device", "Device & diagnostics", "Phone health, self-check, crash report"),
    SettingsPage("backup", "Backup", "Export or import everything")
)

@Composable
fun SettingsScreen(start: String?, a11yOn: Boolean, onOpenAccessibility: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    var page by remember { mutableStateOf(start?.takeIf { it.isNotEmpty() }) }
    // Opened straight onto a page (from the sidebar or a /command): back closes settings.
    val direct = remember { start?.isNotEmpty() == true }
    fun back() { if (page != null && !direct) page = null else onDismiss() }
    BackHandler { back() }
    val current = settingsPages.firstOrNull { it.id == page }

    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { back() }, contentAlignment = Alignment.Center) {
                Text("←", style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink))
            }
            Text(current?.title ?: "Settings", modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = c.ink))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (page) {
                null -> {
                    Spacer(Modifier.height(8.dp))
                    var advanced by remember { mutableStateOf(false) }
                    val shown = settingsPages.filter { it.id in essentialPages } + if (advanced) settingsPages.filter { it.id !in essentialPages } else emptyList()
                    shown.forEachIndexed { i, sp ->
                        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { page = sp.id }.padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sp.title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
                                Text(sp.sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                            }
                            Text("→", color = c.sub, fontSize = 16.sp)
                        }
                    }
                    if (!advanced) Row(Modifier.fillMaxWidth().clickable { advanced = true }.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("Advanced · ${settingsPages.size - essentialPages.size} more", color = c.accent, size = 10.sp)
                        Spacer(Modifier.weight(1f)); Text("↓", color = c.accent, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Label("Blaze " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault(""), color = c.weak, size = 10.sp)
                }
                "ai" -> AiPage()
                "feel" -> FeelPage()
                "connections" -> ConnectionsPage()
                "persona" -> PersonaSection()
                "control" -> ControlPage(a11yOn, onOpenAccessibility)
                "commands" -> CommandsSection()
                "voice" -> { VoiceSection(); VoiceExtras() }
                "orb" -> OrbSection()
                "routines" -> RoutinesSection()
                "memory" -> { MemorySection(); LearningSection() }
                "usage" -> { UsageSection(); CostCap() }
                "privacy" -> SecuritySection()
                "device" -> DevicePage()
                "proactive" -> ProactivePage()
                "specialists" -> SpecialistsPage()
                "terminal" -> TerminalPage()
                "dashboard" -> DashboardPage()
                "backup" -> BackupSection()
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun AiPage() {
    var name by remember { mutableStateOf(Settings.userName) }
    var pid by remember { mutableStateOf(Settings.providerId) }
    var key by remember { mutableStateOf(Settings.apiKey) }
    var model by remember { mutableStateOf(Settings.model) }
    var base by remember { mutableStateOf(Settings.customBase) }
    var think by remember { mutableStateOf(Settings.showReasoning) }
    var fu by remember { mutableStateOf(Settings.followups) }
    val c = LocalBlaze.current

    fun switchTo(newPid: String) {
        Settings.apiKey = key; Settings.model = model
        Settings.providerId = newPid
        pid = newPid; key = Settings.apiKey; model = Settings.model
    }
    // Saved when you leave the page.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            Settings.userName = name; Settings.providerId = pid; Settings.apiKey = key
            Settings.model = model; Settings.customBase = base
        }
    }
    Spacer(Modifier.height(12.dp))
    Field("Your name", name, { name = it; Settings.userName = it }, "What should Blaze call you?")
    ProviderPicker(pid) { switchTo(it) }
    val prov = Providers.byId(pid)
    if (pid == Providers.AUTO) {
        AutoRouterSection()
    } else {
    Field("${prov.name} key", key, { key = it; Settings.apiKey = it }, prov.keyHint, secret = true)
    if (prov.keyPage.isNotBlank()) {
        val uri = LocalUriHandler.current
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Text("No key yet?", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            Label("Get a key →", color = c.accent, size = 11.sp, modifier = Modifier.clickable { uri.openUri(prov.keyPage) })
        }
    }
    if (pid == Providers.CUSTOM) Field("Service address", base, { base = it; Settings.customBase = it }, "https://…/v1/")
    androidx.compose.runtime.key(pid) {
        ModelPicker(model, key, pid, base) { model = it; Settings.model = it }
        QuotaPanel(key, pid, model, base)
    }
    }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Show reasoning", "A brain button reveals how the model thought, when it shares it", think) { think = !think; Settings.showReasoning = think }
    ToggleRow("Suggested follow-ups", "Three tap-to-send ideas under each answer", fu) { fu = !fu; Settings.followups = fu }
    FastLaneSection()
    StepsModelSection()
}

@Composable
private fun ControlPage(a11yOn: Boolean, onOpenAccessibility: () -> Unit) {
    val c = LocalBlaze.current
    var control by remember { mutableStateOf(Settings.phoneControl) }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Phone control", "Open apps, tap, type and scroll for you", control) { control = !control; Settings.phoneControl = control }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().hairline(if (a11yOn) c.hair else c.accent).clickable(onClick = onOpenAccessibility).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (a11yOn) c.accent else c.weak))
        Spacer(Modifier.width(10.dp))
        Text(if (a11yOn) "Accessibility access is on" else "Turn on \u201cBlaze phone control\u201d in Accessibility",
            modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
        Label("Open →", color = c.accent)
    }
    if (!a11yOn) Text("If Android calls it a restricted setting: App info → Blaze → ⋮ → Allow restricted settings, then try again.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub), modifier = Modifier.padding(top = 8.dp))
    SkillsSection()
    NotificationAccessRow()
}

@Composable
private fun CommandsSection() {
    val c = LocalBlaze.current
    var list by remember { mutableStateOf(com.blaze.assistant.agent.Commands.custom()) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    Text("Type / in the chat box to see every command. Make your own: Blaze swaps {input} for whatever you type after it.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    Spacer(Modifier.height(14.dp))
    if (list.isNotEmpty()) {
        Label("Yours", modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.fillMaxWidth().hairline(c.hair)) {
            list.forEach { cmd ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("/" + cmd.name, style = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink))
                        Text(cmd.prompt.orEmpty().take(80), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                    Box(Modifier.clickable { com.blaze.assistant.agent.Commands.deleteCustom(cmd.name); list = com.blaze.assistant.agent.Commands.custom() }
                        .padding(12.dp)) { Label("×", size = 14.sp) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
    Label("New command", modifier = Modifier.padding(bottom = 8.dp))
    Field("Name", name, { name = it }, "standup")
    Spacer(Modifier.height(10.dp))
    Field("Short description", desc, { desc = it }, "Write my daily standup")
    Spacer(Modifier.height(10.dp))
    Field("Prompt", prompt, { prompt = it }, "Turn these notes into a 3-line standup: {input}")
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(46.dp).background(c.ink).clickable {
        com.blaze.assistant.agent.Commands.saveCustom(name, desc, prompt)
        list = com.blaze.assistant.agent.Commands.custom(); name = ""; desc = ""; prompt = ""
    }, contentAlignment = Alignment.Center) { Label("Save command", color = c.paper, size = 11.sp) }
}

@Composable
private fun SquareToggle(on: Boolean) {
    val c = LocalBlaze.current
    val x by animateDpAsState(if (on) 22.dp else 2.dp, label = "knob")
    Box(Modifier.width(46.dp).height(26.dp).background(if (on) c.ink else c.faint).hairline(if (on) c.ink else c.hair)) {
        Box(Modifier.offset(x = x, y = 2.dp).size(22.dp).background(if (on) c.paper else c.weak))
    }
}

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    val c = LocalBlaze.current
    Label(label, modifier = Modifier.padding(bottom = 8.dp))
    Box(Modifier.fillMaxWidth().hairline(c.hair).padding(horizontal = 14.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(placeholder, maxLines = 1,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(14.dp))
}

/**
 * Searchable list of the models this key's plan allows. Nothing is typed by hand:
 * "Check key" lists the key's models and test-calls each one, keeping only those that answer.
 */
@Composable
private fun ModelPicker(selected: String, key: String, pid: String, base: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(ModelCatalog.cached()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun check() {
        if (loading) return
        Settings.providerId = pid; Settings.apiKey = key   // test what's typed in the sheet right now
        if (pid == Providers.CUSTOM) Settings.customBase = base
        loading = true; error = null
        scope.launch {
            val r = ModelCatalog.detect()
            loading = false
            if (r.error != null) error = r.error
            if (r.models.isNotEmpty()) {
                result = r
                if (r.models.none { it.id == selected }) onPick(r.models.first().id)
                open = true
            }
        }
    }
    LaunchedEffect(Unit) { if (result == null && key.isNotBlank()) check() }

    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Model", modifier = Modifier.weight(1f))
        result?.plan?.takeIf { it.isNotBlank() }?.let { Label("Plan · $it", color = c.accent, size = 10.sp) }
    }
    Row(
        Modifier.fillMaxWidth().hairline(if (open) c.ink else c.hair)
            .clickable { if (result == null) check() else open = !open }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            selected.ifBlank { "Choose a model" }, modifier = Modifier.weight(1f), maxLines = 1,
            style = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink)
        )
        Label(if (open) "▲" else "▼", color = c.sub)
    }

    if (open && result != null) {
        val all = result!!.models
        val shown = all.filter { query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) }
        Column(Modifier.fillMaxWidth().hairline(c.hair)) {
            Box(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (query.isEmpty()) Text("Search ${all.size} models", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
                )
            }
            if (shown.isEmpty()) {
                Text("No model matches “$query”.", modifier = Modifier.padding(14.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
            }
            shown.forEach { m ->
                val on = m.id == selected
                Row(
                    Modifier.fillMaxWidth().background(if (on) c.faint else c.paper)
                        .clickable { onPick(m.id); open = false; query = "" }
                        .hairlineBottom(c.hair).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(if (on) c.accent else c.paper))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                        Text(m.id + if (m.note.isNotBlank()) "  ·  ${m.note}" else "",
                            style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                loading -> "Checking which models your key's plan allows…"
                error != null -> error!!
                result != null -> "Only models your key can use are listed."
                key.isBlank() -> "Add your key, then check it to load your models."
                else -> "Check your key to load the models it can use."
            },
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp,
                color = if (error != null && !loading) c.accent else c.sub)
        )
        Spacer(Modifier.width(10.dp))
        Label(if (loading) "Checking" else if (result == null) "Check key" else "Refresh",
            color = if (key.isBlank() || loading) c.weak else c.accent, size = 11.sp,
            modifier = Modifier.clickable(enabled = key.isNotBlank() && !loading) { check() }.padding(6.dp))
    }
}

/** Provider dropdown: the built-in services plus a custom OpenAI-compatible one. */
@Composable
private fun ProviderPicker(pid: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    var open by remember { mutableStateOf(false) }
    Label("AI provider", modifier = Modifier.padding(bottom = 8.dp))
    Row(
        Modifier.fillMaxWidth().hairline(if (open) c.ink else c.hair).clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(Providers.byId(pid).name, modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
        Label(if (open) "▲" else "▼", color = c.sub)
    }
    if (open) {
        Column(Modifier.fillMaxWidth().hairline(c.hair)) {
            Providers.all.forEach { p ->
                val on = p.id == pid
                val hasKey = Settings.keyFor(p.id).isNotBlank()
                Row(
                    Modifier.fillMaxWidth().background(if (on) c.faint else c.paper)
                        .clickable { onPick(p.id); open = false }
                        .hairlineBottom(c.hair).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(if (on) c.accent else c.paper))
                    Spacer(Modifier.width(10.dp))
                    Text(p.name, modifier = Modifier.weight(1f),
                        style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                    if (hasKey) Label("Key saved", color = c.accent, size = 9.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

/** Plan & quota: balance, rate limits and plan as the provider reports them to this key. */
@Composable
private fun QuotaPanel(key: String, pid: String, model: String, base: String) {
    val c = LocalBlaze.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var report by remember { mutableStateOf<Quota.Report?>(null) }
    var loading by remember { mutableStateOf(false) }
    val prov = Providers.byId(pid)

    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Plan & quota", modifier = Modifier.weight(1f))
        Label(if (loading) "Checking" else "Check", color = if (key.isBlank() || loading) c.weak else c.accent, size = 11.sp,
            modifier = Modifier.clickable(enabled = key.isNotBlank() && !loading) {
                Settings.providerId = pid; Settings.apiKey = key; Settings.model = model
                if (pid == Providers.CUSTOM) Settings.customBase = base
                loading = true
                scope.launch { report = Quota.check(); loading = false }
            }.padding(6.dp))
    }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        val r = report
        if (r == null) {
            Text(if (key.isBlank()) "Add your key to see your plan and limits." else "Tap Check to see your plan, limits and balance.",
                modifier = Modifier.padding(14.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        } else {
            r.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Label(row.label, modifier = Modifier.weight(1f), size = 10.sp)
                    Text(row.value, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.ink))
                }
            }
            r.note?.let {
                Text(it, modifier = Modifier.padding(14.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
            }
        }
        if (prov.quotaPage.isNotBlank()) {
            Row(Modifier.fillMaxWidth().clickable { uri.openUri(prov.quotaPage) }.padding(14.dp)) {
                Text("Full usage dashboard", modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
                Label("Open →", color = c.accent, size = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}


@Composable
internal fun ToggleRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit) {
    val c = LocalBlaze.current
    Row(
        Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable(onClick = onToggle).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
            Text(sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        SquareToggle(on)
    }
}

@Composable
private fun VoiceSection() {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var speak by remember { mutableStateOf(Settings.speakReplies) }
    var wake by remember { mutableStateOf(Settings.wakeWord) }
    var isDefault by remember { mutableStateOf(BlazeInteractionService.isDefault(ctx)) }
    val dl by WakeModel.progress.collectAsState()
    var modelReady by remember { mutableStateOf(WakeModel.ready(ctx)) }
    var err by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { isDefault = BlazeInteractionService.isDefault(ctx); kotlinx.coroutines.delay(1500) }
    }
    val micPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    fun setWake(on: Boolean) {
        err = ""
        wake = on; Settings.wakeWord = on
        if (!on) { WakeWordService.stop(ctx); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
        if (WakeModel.ready(ctx)) { WakeWordService.start(ctx); return }
        scope.launch {
            val r = WakeModel.download(ctx)
            modelReady = WakeModel.ready(ctx)
            if (r.isSuccess && modelReady) WakeWordService.start(ctx)
            else { err = "Download failed. Check your connection and try again."; wake = false; Settings.wakeWord = false }
        }
    }

    Spacer(Modifier.height(22.dp))
    Label("Voice & assistant", modifier = Modifier.padding(bottom = 4.dp))
    ToggleRow("Speak replies", "Read answers aloud when you ask by voice", speak) { speak = !speak; Settings.speakReplies = speak }
    ToggleRow(
        "\u201cHey Blaze\u201d",
        when {
            dl in 0..99 -> "Downloading voice model\u2026 $dl%"
            wake && modelReady -> "Listening offline for \u201cHey Blaze\u201d or \u201cWake up Blaze\u201d"
            else -> "Wake Blaze hands-free. One-time ${WakeModel.SIZE_MB} MB offline download"
        },
        wake
    ) { if (dl !in 0..99) setWake(!wake) }
    if (err.isNotEmpty()) Text(err, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent),
        modifier = Modifier.padding(top = 8.dp))

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth().hairline(if (isDefault) c.hair else c.accent)
            .clickable { BlazeInteractionService.openPicker(ctx) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(if (isDefault) c.accent else c.weak))
        Spacer(Modifier.width(10.dp))
        Text(
            if (isDefault) "Blaze is your default assistant" else "Make Blaze your default assistant",
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body)
        )
        Label("Open →", color = c.accent)
    }
    if (!isDefault) Text(
        "Pick Digital assistant app \u2192 Blaze. Then long-press power or home to talk to Blaze instead of Gemini.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub),
        modifier = Modifier.padding(top = 8.dp)
    )
}


@Composable
private fun OrbSection() {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(Overlay.orbShowing) }
    var allowed by remember { mutableStateOf(Overlay.canDrawOverApps(ctx)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { allowed = Overlay.canDrawOverApps(ctx); on = Overlay.orbShowing; kotlinx.coroutines.delay(1200) }
    }
    ToggleRow("Floating orb", if (on) "Blaze stays on screen. Tap to chat, hold to talk, drag to move"
        else "Keep Blaze on screen while you use other apps", on) {
        if (on) { Overlay.hideOrb(ctx); on = false }
        else if (!Overlay.showOrb(ctx)) Overlay.requestOverlayPermission(ctx) else on = true
    }
    if (!allowed) Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).hairline(c.hair).clickable { Overlay.requestOverlayPermission(ctx) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Allow “Display over other apps” so you can type in the orb's chat", modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
        Label("Allow →", color = c.accent, size = 11.sp)
    }
}

@Composable
private fun PersonaSection() {
    val c = LocalBlaze.current
    var pid by remember { mutableStateOf(Settings.personaId) }
    var custom by remember { mutableStateOf(Settings.customPersona) }
    var stream by remember { mutableStateOf(Settings.streamReplies) }
    Spacer(Modifier.height(22.dp))
    Label("Personality", modifier = Modifier.padding(bottom = 8.dp))
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        com.blaze.assistant.agent.Personas.all.forEach { p ->
            Row(Modifier.fillMaxWidth().background(if (p.id == pid) c.faint else c.paper)
                .clickable { pid = p.id; Settings.personaId = p.id }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (p.id == pid) c.accent else Color.Transparent))
                Spacer(Modifier.width(10.dp))
                Text(p.name, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontSize = 15.sp, color = c.ink))
                Text(p.blurb, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
    if (pid == com.blaze.assistant.agent.Personas.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        Field("Describe Blaze's personality", custom, { custom = it; Settings.customPersona = it },
            "e.g. Talk like a calm big brother, mix in some Nyanja, keep it short")
    }
    Spacer(Modifier.height(6.dp))
    var dots by remember { mutableStateOf(Settings.particles) }
    ToggleRow("Background particles", "Drifting dots. Turn off for the smoothest, lightest feel", dots) { dots = !dots; Settings.particles = dots }
    ToggleRow("Stream replies", "Words appear as Blaze writes them", stream) { stream = !stream; Settings.streamReplies = stream }
}

@Composable
private fun MemorySection() {
    val c = LocalBlaze.current
    val facts by com.blaze.assistant.agent.Memory.facts.collectAsState()
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Label("Memory", modifier = Modifier.weight(1f))
        if (facts.isNotEmpty()) Label("Forget all", color = c.accent, size = 10.sp,
            modifier = Modifier.clickable { com.blaze.assistant.agent.Memory.clear() })
    }
    if (facts.isEmpty()) Text("Nothing yet. Tell Blaze things like \u201cremember my sister is Mary\u201d and it keeps them across chats.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        facts.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
                Box(Modifier.clickable { com.blaze.assistant.agent.Memory.remove(i) }.padding(12.dp)) { Label("×", size = 14.sp) }
            }
        }
    }
}

@Composable
private fun BackupSection() {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var msg by remember { mutableStateOf("") }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        msg = try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(Settings.exportJson().toByteArray()) }
            "Saved. Keep this file private: it holds your API keys."
        } catch (e: Exception) { "Couldn't save: ${e.message}" }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        msg = try {
            val n = Settings.importJson(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText())
            com.blaze.assistant.agent.Memory.init(ctx)
            "Restored $n settings. Close and reopen Settings to see them."
        } catch (e: Exception) { "That file isn't a Blaze backup." }
    }
    Spacer(Modifier.height(22.dp))
    Label("Backup", modifier = Modifier.padding(bottom = 8.dp))
    Text("Updates keep everything automatically. Export only if you'll reinstall or switch phones.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable { exporter.launch("blaze-backup.json") },
            contentAlignment = Alignment.Center) { Label("Export", color = c.ink, size = 11.sp) }
        Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable { importer.launch(arrayOf("application/json", "*/*")) },
            contentAlignment = Alignment.Center) { Label("Import", color = c.ink, size = 11.sp) }
    }
    if (msg.isNotEmpty()) Text(msg, modifier = Modifier.padding(top = 8.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent))
}


@Composable
private fun SkillsSection() {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    Spacer(Modifier.height(22.dp))
    Label("Skills", modifier = Modifier.padding(bottom = 8.dp))
    val subs = mapOf("Contacts" to "Find people by name", "Messages" to "Read and send texts (asks before sending)",
        "Calls" to "Recent calls, place calls (asks first)", "Calendar" to "Read and add events")
    com.blaze.assistant.phone.Skills.groups.forEach { (name, perms) ->
        val on = remember(tick) { com.blaze.assistant.phone.Skills.granted(ctx, perms) }
        ToggleRow(name, subs[name] ?: "", on) {
            if (!on) launcher.launch(perms)
            else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Text("Quick settings (flashlight, volume, battery, Wi-Fi panel) work without any permission.",
        modifier = Modifier.padding(top = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
}

@Composable
private fun RoutinesSection() {
    val c = LocalBlaze.current
    val list by com.blaze.assistant.routine.Routines.all.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("07:00") }
    var days by remember { mutableStateOf(31) }
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Label("Routines", modifier = Modifier.weight(1f))
        Label(if (adding) "Cancel" else "+ New", color = c.accent, size = 10.sp, modifier = Modifier.clickable { adding = !adding })
    }
    if (list.isEmpty() && !adding) Text("Nothing scheduled. Try saying \u201cevery weekday at 7, brief me on my calendar and messages\u201d.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    if (list.isNotEmpty()) Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        list.forEach { r ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = TextStyle(fontFamily = Type.Display, fontSize = 15.sp, color = if (r.enabled) c.ink else c.weak))
                    Label("${r.timeLabel()} · ${r.daysLabel()}", size = 9.sp)
                }
                Box(Modifier.clickable { com.blaze.assistant.routine.Routines.setEnabled(r.id, !r.enabled) }.padding(10.dp)) {
                    Label(if (r.enabled) "On" else "Off", color = if (r.enabled) c.accent else c.sub, size = 10.sp)
                }
                Box(Modifier.clickable { com.blaze.assistant.routine.Routines.delete(r.id) }.padding(12.dp)) { Label("×", size = 14.sp) }
            }
        }
    }
    if (adding) {
        Spacer(Modifier.height(10.dp))
        Field("Name", name, { name = it }, "Morning brief")
        Spacer(Modifier.height(10.dp))
        Field("What should Blaze do?", prompt, { prompt = it }, "Summarise today's calendar and unread texts")
        Spacer(Modifier.height(10.dp))
        Field("Time (24h)", time, { time = it }, "07:00")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                val on = days and (1 shl i) != 0
                Box(Modifier.weight(1f).height(38.dp).background(if (on) c.ink else c.paper).hairline(if (on) c.ink else c.hair)
                    .clickable { days = days xor (1 shl i) }, contentAlignment = Alignment.Center) {
                    Label(d, color = if (on) c.paper else c.sub, size = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(46.dp).background(c.ink).clickable {
            val parts = time.split(":")
            val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 7
            val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (prompt.isNotBlank()) {
                com.blaze.assistant.routine.Routines.add(name, prompt, h, m, days)
                adding = false; name = ""; prompt = ""
            }
        }, contentAlignment = Alignment.Center) { Label("Save routine", color = c.paper, size = 11.sp) }
    }
}

@Composable
private fun UsageSection() {
    val c = LocalBlaze.current
    val v by com.blaze.assistant.agent.Usage.version.collectAsState()
    val today = remember(v) { com.blaze.assistant.agent.Usage.today() }
    val month = remember(v) { com.blaze.assistant.agent.Usage.month() }
    fun fmt(t: com.blaze.assistant.agent.Usage.Totals): String {
        val tok = t.input + t.output
        val tk = if (tok >= 1_000_000) "%.1fM".format(tok / 1e6) else if (tok >= 1000) "%.1fk".format(tok / 1e3) else "$tok"
        val cost = if (t.priced) (if (t.cost < 0.01 && t.cost > 0) "<$0.01" else "$%.2f".format(t.cost)) else "price unknown"
        return "$tk tokens · ${t.calls} calls · $cost"
    }
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Label("Usage", modifier = Modifier.weight(1f))
        Label("Reset", color = c.sub, size = 10.sp, modifier = Modifier.clickable { com.blaze.assistant.agent.Usage.reset() })
    }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        listOf("Today" to today, "This month" to month).forEach { (k, t) ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontSize = 15.sp, color = c.ink))
                Text(fmt(t), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
            }
        }
    }
    Text("Estimated from your provider's counts and list prices. Free tiers show $0.00.",
        modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
}

@Composable
private fun SecuritySection() {
    var lock by remember { mutableStateOf(Settings.appLock) }
    var risky by remember { mutableStateOf(Settings.confirmRisky) }
    Spacer(Modifier.height(22.dp))
    Label("Privacy & safety", modifier = Modifier.padding(bottom = 4.dp))
    ToggleRow("Lock Blaze", "Fingerprint, face or PIN to open the app", lock) { lock = !lock; Settings.appLock = lock }
    ToggleRow("Ask before risky actions", "Blaze asks before tapping Send, Pay, Buy, Delete or Post", risky) { risky = !risky; Settings.confirmRisky = risky }
}
