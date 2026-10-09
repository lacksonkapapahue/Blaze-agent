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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.FastLane
import com.blaze.assistant.agent.FreeRouter
import com.blaze.assistant.agent.Learning
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.ScreenMemory
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.Connectors
import com.blaze.assistant.connect.Mcp
import com.blaze.assistant.notify.NotificationBrain
import kotlinx.coroutines.launch

@Composable
private fun Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
private fun Button(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxWidth().height(44.dp).background(if (primary) c.ink else c.paper).hairline(if (primary) c.ink else c.hair)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { Label(text, color = if (primary) c.paper else c.ink, size = 11.sp) }
}

@Composable
private fun Section(title: String) { Spacer(Modifier.height(24.dp)); Label(title, modifier = Modifier.padding(bottom = 8.dp)) }

// ---------------- Auto (free routing) ----------------

@Composable
fun AutoRouterSection() {
    val c = LocalBlaze.current
    var chain by remember { mutableStateOf(FreeRouter.entries()) }
    val health by FreeRouter.status.collectAsState()
    Note("Blaze uses the first model in this chain and moves down it when one is rate-limited, out of quota, slow or failing, then comes back once it cools down. Only providers with a key saved are used. Free tiers have their own limits and terms.")
    Button("Use my free stack") { FreeRouter.presetFreeStack(); chain = FreeRouter.entries() }
    Spacer(Modifier.height(12.dp))
    if (chain.isEmpty()) Note("Nothing to route yet. Pick Google Gemini, Groq or OpenRouter above, paste a free key, then come back to Auto.")
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        chain.forEachIndexed { i, (pid, model) ->
            val h = health["$pid|$model"]
            val cooling = (h?.coolUntil ?: 0) > System.currentTimeMillis()
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(6.dp).background(when { h == null -> c.weak; cooling -> c.sub; else -> c.accent }))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(Providers.byId(pid).name.substringBefore(" ("), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text(model + (h?.let { if (cooling) " · resting" else if (it.avgMs > 0) " · ${it.avgMs} ms" else "" } ?: ""), maxLines = 1,
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
                }
                Box(Modifier.clickable(enabled = i > 0) { chain = chain.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }; FreeRouter.save(chain) }.padding(10.dp)) { Label("↑", size = 13.sp) }
                Box(Modifier.clickable { FreeRouter.removed(pid); chain = chain.filterIndexed { j, _ -> j != i }; FreeRouter.save(chain) }.padding(10.dp)) { Label("×", size = 13.sp) }
            }
        }
    }
    Note("Last resort with no key: Pollinations (plain chat only, no phone actions).", top = 8)
}

// ---------------- Fast lane ----------------

@Composable
fun FastLaneSection() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prog by FastLane.progress.collectAsState()
    var on by remember { mutableStateOf(Settings.fastLane) }
    var ready by remember(prog) { mutableStateOf(FastLane.ready(ctx)) }
    var err by remember { mutableStateOf("") }
    Section("Fast lane (on-device)")
    if (!FastLane.supported(ctx)) { Note("This phone's processor isn't supported by the on-device model."); return }
    Note("Needle, a tiny on-phone model, handles direct commands like \u201copen WhatsApp\u201d, \u201cflashlight on\u201d or \u201ctimer 5 minutes\u201d in under a second, even offline. Anything else goes to your AI model.")
    if (!ready) {
        Button(if (prog in 0..99) "Downloading… $prog%" else "Download model (${FastLane.SIZE_MB} MB)") {
            if (prog !in 0..99) scope.launch { err = FastLane.download(ctx).exceptionOrNull()?.message.orEmpty(); ready = FastLane.ready(ctx) }
        }
        if (err.isNotBlank()) Note("Download failed: $err", top = 6)
    } else {
        ToggleRow("Use the fast lane", "Model downloaded · runs on this phone", on) { on = !on; Settings.fastLane = on; if (!on) FastLane.stop() }
        Label("Remove model", color = c.accent, size = 10.sp, modifier = Modifier.padding(top = 4.dp).clickable { FastLane.delete(ctx); ready = false })
    }
}

// ---------------- Steps model ----------------

@Composable
fun StepsModelSection() {
    var pid by remember { mutableStateOf(Settings.stepsProvider) }
    var model by remember { mutableStateOf(Settings.stepsModel) }
    val c = LocalBlaze.current
    Section("Model for phone steps")
    Note("Optional: a quicker model for the in-between turns while Blaze taps through an app (e.g. a Flash-Lite model). Leave on \u201cSame as main\u201d to use your main model.")
    val options = listOf("" to "Same as main") + Providers.all.filter { it.id == Providers.AUTO || Settings.keyFor(it.id).isNotBlank() }.map { it.id to it.name }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        options.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth().background(if (id == pid) c.faint else c.paper).hairlineBottom(c.hair)
                .clickable { pid = id; Settings.stepsProvider = id }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (id == pid) c.accent else c.paper)); Spacer(Modifier.width(10.dp))
                Text(name, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
            }
        }
    }
    if (pid.isNotBlank() && pid != Providers.AUTO) { Spacer(Modifier.height(10.dp)); Field("Steps model id", model, { model = it; Settings.stepsModel = it }, Settings.modelFor(pid).ifBlank { "model id" }) }
}

// ---------------- Connections ----------------

@Composable
fun ConnectionsPage() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val st by Connectors.state.collectAsState()
    var open by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    Spacer(Modifier.height(10.dp))
    Note("Let Blaze use your accounts. You create a free app key with each service once (steps inside); sign-in tokens stay encrypted on this phone. Sending or posting always asks you first.")
    Connectors.all.forEach { conn ->
        val on = remember(tick, st) { Connectors.connected(conn.id) }
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { open = if (open == conn.id) "" else conn.id }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (on) c.accent else c.weak)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(conn.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                Text(st[conn.id]?.takeIf { it.isNotBlank() } ?: conn.blurb, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            Label(if (on) "On" else "Set up", color = if (on) c.accent else c.sub, size = 10.sp)
        }
        if (open == conn.id) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Note(conn.help)
            Label("Open setup page →", color = c.accent, size = 10.sp, modifier = Modifier.padding(bottom = 12.dp).clickable { uri.openUri(conn.setupUrl) })
            if (on) Button("Disconnect", primary = false) { Connectors.disconnect(conn.id); tick++ }
            else if (conn.kind == Connectors.Kind.TOKEN) {
                var tok by remember { mutableStateOf("") }
                Field("Token", tok, { tok = it }, if (conn.id == "slack") "xoxp-…" else "ntn_…", secret = true)
                Button("Save") { Connectors.saveToken(conn.id, tok); tok = ""; tick++ }
            } else {
                var cid by remember { mutableStateOf(Connectors.clientId(conn.id)) }
                var sec by remember { mutableStateOf(Connectors.clientSecret(conn.id)) }
                Field("Client ID", cid, { cid = it; Connectors.setClientId(conn.id, it) }, "Paste client ID")
                if (conn.needsSecret) Field("Client secret", sec, { sec = it; Connectors.setClientSecret(conn.id, it) }, "Paste client secret", secret = true)
                Button("Connect") { scope.launch { Connectors.connect(ctx, conn.id); tick++ } }
            }
        }
    }
    McpSection()
}

@Composable
private fun McpSection() {
    val c = LocalBlaze.current
    val scope = rememberCoroutineScope()
    val servers by Mcp.servers.collectAsState()
    val status by Mcp.status.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var tok by remember { mutableStateOf("") }
    Section("MCP servers")
    Note("Add any remote MCP server (Streamable HTTP). Its tools become Blaze tools. You can also point Custom provider at a self-hosted OmniRoute (http://<your-pc>:20128/v1) for even more free routing.")
    if (servers.isNotEmpty()) Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        servers.forEach { s ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (s.enabled) c.ink else c.weak))
                    Text(status[s.id] ?: s.url, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
                }
                Box(Modifier.clickable { scope.launch { Mcp.refresh(s) } }.padding(8.dp)) { Label("Test", size = 10.sp) }
                Box(Modifier.clickable { Mcp.toggle(s.id) }.padding(8.dp)) { Label(if (s.enabled) "On" else "Off", color = if (s.enabled) c.accent else c.weak, size = 10.sp) }
                Box(Modifier.clickable { Mcp.remove(s.id) }.padding(10.dp)) { Label("×", size = 13.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Field("Name", name, { name = it }, "e.g. Zapier")
    Field("Server URL", url, { url = it }, "https://…/mcp")
    Field("Bearer token (optional)", tok, { tok = it }, "Optional", secret = true)
    Button("Add server") {
        if (name.isNotBlank() && url.isNotBlank()) {
            Mcp.add(name, url, tok)
            val s = Mcp.servers.value.lastOrNull(); name = ""; url = ""; tok = ""
            if (s != null) scope.launch { Mcp.refresh(s) }
        }
    }
}

// ---------------- Learning ----------------

@Composable
fun LearningSection() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val skills by Learning.skills.collectAsState()
    val pending by Learning.pending.collectAsState()
    var auto by remember { mutableStateOf(Settings.autoLearn) }
    var ask by remember { mutableStateOf(Settings.learnApproval) }
    var screen by remember { mutableStateOf(Settings.screenMemory) }
    var shown by remember { mutableStateOf("") }
    Section("Learning")
    ToggleRow("Learn from chats", "After each answer Blaze notes lasting facts, writes a one-line chat summary, and saves tasks it worked out as skills", auto) { auto = !auto; Settings.autoLearn = auto }
    ToggleRow("Ask before saving", "Changes wait here for your OK instead of saving straight away", ask) { ask = !ask; Settings.learnApproval = ask }
    if (pending.isNotEmpty()) {
        Section("Waiting for your OK")
        Column(Modifier.fillMaxWidth().hairline(c.accent)) {
            pending.forEach { p ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
                    Box(Modifier.clickable { Learning.approve(p.id) }.padding(10.dp)) { Label("Keep", color = c.accent, size = 10.sp) }
                    Box(Modifier.clickable { Learning.reject(p.id) }.padding(10.dp)) { Label("×", size = 13.sp) }
                }
            }
        }
    }
    Section("Skills Blaze learned")
    if (skills.isEmpty()) Note("None yet. When Blaze works out a multi-step task (like ordering your usual ride), it saves the steps so next time is faster. Recorded phone steps can be replayed without the AI.")
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        skills.sortedByDescending { it.uses }.forEach { s ->
            Column(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { shown = if (shown == s.name) "" else s.name }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.replay != null) "  ▶" else "", style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, color = c.ink))
                        Text(s.description + " · used ${s.uses}×", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                    Box(Modifier.clickable { com.blaze.assistant.agent.Agent.send("Run my skill \"${s.name}\"") }.padding(10.dp)) { Label("Run", color = c.accent, size = 10.sp) }
                    Box(Modifier.clickable { Learning.deleteSkill(s.name) }.padding(10.dp)) { Label("×", size = 13.sp) }
                }
                if (shown == s.name) Text(s.steps, modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.body))
            }
        }
    }
    Section("Screen memory")
    ToggleRow("Remember what I see", "Keeps short text snapshots of screens when you switch apps, only on this phone (needs phone control). Password screens are skipped.", screen) { screen = !screen; Settings.screenMemory = screen }
    var count by remember(screen) { mutableStateOf(ScreenMemory.count(ctx)) }
    if (count > 0) Label("Clear $count snapshots", color = c.accent, size = 10.sp, modifier = Modifier.padding(top = 4.dp).clickable { ScreenMemory.clear(ctx); count = 0 })
}

// ---------------- Notification access ----------------

@Composable
fun NotificationAccessRow() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(NotificationBrain.enabled(ctx)) }
    LaunchedEffect(Unit) { on = NotificationBrain.enabled(ctx) }
    Section("Notification brain")
    Row(Modifier.fillMaxWidth().hairline(if (on) c.hair else c.accent).clickable { ctx.startActivity(NotificationBrain.settingsIntent()) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (on) c.accent else c.weak)); Spacer(Modifier.width(10.dp))
        Text(if (on) "Notification access is on" else "Let Blaze read notifications", modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
        Label("Open →", color = c.accent)
    }
    Note("Ask \u201cwhat did I miss?\u201d or \u201creply to Mary on WhatsApp\u201d. Kept in memory only, never saved; codes are hidden.", top = 8)
}
