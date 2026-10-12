package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Project
import kotlinx.coroutines.delay

/*
 * Blaze 0.12 shell: a calm, messaging-app layout (Desktop 1.0 redesign, part A).
 * Everything here is stateless so the screenshot tests can draw it with sample data.
 */

// ---------------------------------------------------------------- top bar

@Composable
fun ShellTopBar(
    mode: String, onMode: (String) -> Unit, onLogo: () -> Unit, onSearch: () -> Unit,
    routerOk: Boolean, onRouter: () -> Unit, avatar: String, onAvatar: () -> Unit,
    mood: Mood = Mood.IDLE, viewChip: String = "", onExit: () -> Unit = {},
) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineBottom(c.hair)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(onClick = onLogo), contentAlignment = Alignment.Center) { BrandMark(mood, 26.dp) }
            Spacer(Modifier.width(6.dp))
            Pill(mode, onMode)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clickable(onClick = onSearch), contentAlignment = Alignment.Center) { SearchGlyph(c.ink) }
            Box(Modifier.size(32.dp).clickable(onClick = onRouter), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(if (routerOk) c.accent else c.weak))
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(34.dp).background(c.ink).clickable(onClick = onAvatar), contentAlignment = Alignment.Center) {
                Text(avatar.take(1).uppercase().ifBlank { "B" }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.paper))
            }
        }
        if (viewChip.isNotBlank()) Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Label(viewChip.uppercase(), color = c.ink, size = 10.sp, modifier = Modifier.weight(1f))
            Row(Modifier.hairline(c.hair).clickable(onClick = onExit).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("✕  Exit", color = c.ink, size = 10.sp)
            }
        }
    }
}

@Composable
private fun Pill(mode: String, onMode: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.hairline(c.hair).padding(2.dp)) {
        listOf("chat" to "Chat", "projects" to "Projects").forEach { (id, label) ->
            val on = mode == id
            Box(Modifier.background(if (on) c.ink else Color.Transparent).clickable { onMode(id) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (on) c.paper else c.sub))
            }
        }
    }
}

@Composable
fun SearchGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 10f
        drawCircle(color, 3.3f * u, Offset(4.2f * u, 4.2f * u), style = Stroke(1.4f * u))
        drawLine(color, Offset(6.7f * u, 6.7f * u), Offset(9.4f * u, 9.4f * u), strokeWidth = 1.6f * u)
    }
}

// ---------------------------------------------------------------- avatar menu

data class MenuItem(val id: String, val label: String, val sub: String = "")

/** Account, Settings, BlazeRouter, Theme, Check for updates, Sign out — dropped from the avatar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AvatarMenu(name: String, email: String, items: List<MenuItem>, onPick: (String) -> Unit, onLongPick: (String) -> Unit = {}, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 10.dp).width(268.dp).background(c.paper).hairline(c.hair)
            .clickable(remember { MutableInteractionSource() }, null) { }) {
            Column(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(name.ifBlank { "Not signed in" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                if (email.isNotBlank()) Text(email, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            items.forEach { m ->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onPick(m.id) }, onLongClick = { onLongPick(m.id) })
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = if (m.id == "signout") c.accent else c.ink))
                    if (m.sub.isNotBlank()) Label(m.sub, color = c.sub, size = 9.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- /views picker

/** The /views picker: filter box + numbered rows. Opens above the composer. */
@Composable
fun ViewsSheet(badges: Map<String, String>, onPick: (BlazeView) -> Unit, onDismiss: () -> Unit, autoFocus: Boolean = true) {
    val c = LocalBlaze.current
    var q by remember { mutableStateOf("") }
    val rows = remember(q) { Views.filter(q) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.ink)) {
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchGlyph(c.sub, 14.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (q.isEmpty()) Text("Search views", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { rows.firstOrNull()?.let(onPick) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            Label("ESC", color = c.weak, size = 9.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, v ->
                Row(Modifier.fillMaxWidth().background(if (i == 0 && q.isNotEmpty()) c.faint else Color.Transparent)
                    .clickable { onPick(v) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                        Text(v.desc, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                    badges[v.id]?.takeIf { it.isNotBlank() }?.let { Label(it, color = c.accent, size = 9.sp) }
                }
            }
            if (rows.isEmpty()) Text("No view matches.", modifier = Modifier.padding(16.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
fun SearchScreen(chats: List<ChatMeta>, onChat: (String) -> Unit, onView: (BlazeView) -> Unit, onDismiss: () -> Unit, initial: String = "", autoFocus: Boolean = true) {
    val c = LocalBlaze.current
    var q by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    val views = remember(q) { if (q.isBlank()) emptyList() else Views.filter(q).take(5) }
    val found = remember(q, chats) {
        if (q.isBlank()) chats.take(12) else chats.filter { it.title.contains(q, true) || it.preview.contains(q, true) }.take(30)
    }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Text("←", style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink))
            }
            Box(Modifier.weight(1f).hairline(c.hair).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (q.isEmpty()) Text("Search chats and views", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (views.isNotEmpty()) {
                Label("Views", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
                views.forEach { v ->
                    Row(Modifier.fillMaxWidth().clickable { onView(v) }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(c.accent)); Spacer(Modifier.width(12.dp))
                        Text(v.name, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                        Spacer(Modifier.width(10.dp))
                        Text(v.desc, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                }
            }
            Label(if (q.isBlank()) "Recent chats" else "Chats", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
            found.forEach { ch -> ChatRow(ch, busy = false) { onChat(ch.id) } }
            if (found.isEmpty()) Text("No chats match.", modifier = Modifier.padding(20.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
    }
}

@Composable
fun ChatRow(ch: ChatMeta, busy: Boolean, current: Boolean = false, chip: String = "", onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().background(if (current) c.faint else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ch.pinned) { Box(Modifier.size(5.dp).background(c.accent)); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f)) {
            Text(ch.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
            if (ch.preview.isNotBlank()) Text(ch.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        if (busy) Label("Working", color = c.accent, size = 9.sp)
        else if (chip.isNotBlank()) Label(chip, color = c.sub, size = 9.sp)
    }
}

// ---------------------------------------------------------------- recent chats drawer (Chat mode rail)

@Composable
fun RecentRail(chats: List<ChatMeta>, busy: Set<String>, current: String, onNew: () -> Unit, onChat: (String) -> Unit, onAll: () -> Unit, onClose: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.width(304.dp).fillMaxSize().background(c.paper).hairline(c.hair).clickable(remember { MutableInteractionSource() }, null) { }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Recent chats", color = c.ink, size = 10.sp, modifier = Modifier.weight(1f))
            Label("×", size = 16.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(46.dp).background(c.ink).clickable(onClick = onNew), contentAlignment = Alignment.Center) {
            Label("+  New chat", color = c.paper, size = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            chats.take(40).forEach { ch -> ChatRow(ch, ch.id in busy, ch.id == current) { onChat(ch.id) } }
            if (chats.isEmpty()) Text("No chats yet", modifier = Modifier.padding(20.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).clickable(onClick = onAll).padding(horizontal = 20.dp, vertical = 14.dp)) {
            Label("All chats  →", color = c.accent, size = 10.sp)
        }
    }
}

// ---------------------------------------------------------------- the one live work card

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

/**
 * ONE live card per turn: "● Working · 4 steps · 1m 12s ›". Tap to see each step.
 * When done: "Worked · 13 steps · 3m 11s ›" and the answer follows as plain text.
 * [exact] (Settings › Show every step) adds each step's exact tool call under it.
 */
@Composable
fun WorkCard(steps: List<ChatMessage>, live: Boolean, startAt: Long, endAt: Long, status: String = "", exact: Boolean = false, now: Long? = null) {
    val c = LocalBlaze.current
    var open by rememberSaveable(steps.firstOrNull()?.id ?: -1L) { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(now ?: System.currentTimeMillis()) }
    if (live && now == null) LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    val end = if (live) (now ?: tick) else endAt
    val n = steps.size
    val count = if (n == 1) "1 step" else "$n steps"
    val time = if (startAt > 0 && end >= startAt) " · " + formatDuration(end - startAt) else ""
    val pulse = if (live) rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value else 1f
    Column(Modifier.fillMaxWidth().widthIn(max = 520.dp).hairline(if (live) c.accent.copy(alpha = 0.55f) else c.hair)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = n > 0) { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).alpha(pulse).background(if (live) c.accent else c.weak))
            Spacer(Modifier.width(10.dp))
            Text((if (live) "Working" else "Worked") + (if (n > 0) " · $count" else "") + time, modifier = Modifier.weight(1f), maxLines = 1,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (live) c.ink else c.sub))
            if (n > 0) {
                val rot by animateFloatAsState(if (open) 90f else 0f, label = "chev")
                Text("›", modifier = Modifier.rotate(rot), style = TextStyle(fontFamily = Type.Display, fontSize = 18.sp, color = c.sub))
            }
        }
        val line = status.ifBlank { steps.lastOrNull()?.text.orEmpty() }
        if (live && !open && line.isNotBlank()) Box(Modifier.fillMaxWidth().padding(start = 29.dp, end = 12.dp, bottom = 10.dp)) {
            Shimmer(line)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.Top) {
                        Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
                            if (exact && s.meta.isNotBlank()) Text(s.meta, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- bubbles

/** User message: right-aligned ink bubble (paper text). */
@Composable
fun UserBubble(text: String, quoted: String = "") {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(Modifier.widthIn(max = 300.dp).background(c.ink).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (quoted.isNotEmpty()) {
                Text(quoted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.paper.copy(alpha = 0.65f)))
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp, color = c.paper))
            }
        }
    }
}

// ---------------------------------------------------------------- composer

/** What the + menu offers. [id] is handled by the screen. */
data class PlusItem(val id: String, val label: String, val sub: String = "", val on: Boolean = false)

fun plusItems(work: String, approval: String, model: String, hasClipImage: Boolean): List<PlusItem> = buildList {
    add(PlusItem("camera", "Camera", "Take a photo"))
    add(PlusItem("photos", "Photos", "Pick pictures"))
    add(PlusItem("files", "Files", "Photos, PDFs, text"))
    add(PlusItem("atfile", "@ files", "Mention a file from Blaze's home"))
    if (hasClipImage) add(PlusItem("paste", "Paste image", "From the clipboard"))
    add(PlusItem("voice", "Voice", "Talk instead of typing"))
    add(PlusItem("call", "Call Blaze", "Hands-free voice call"))
    add(PlusItem("plan", if (work == "plan") "Plan mode" else "Build mode", if (work == "plan") "Read and look only · tap for Build" else "Full access · tap for Plan", on = work == "plan"))
    add(PlusItem("approval", if (approval == "autopilot") "Autopilot" else "Auto", if (approval == "autopilot") "Works on without asking · tap for Auto" else "Asks before risky steps · tap for Autopilot", on = approval == "autopilot"))
    add(PlusItem("model", "Model", model))
    add(PlusItem("views", "Views", "Memory, jobs, design, accounts…"))
    add(PlusItem("new", "New chat", ""))
}

@Composable
fun PlusMenu(items: List<PlusItem>, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.ink).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        items.forEach { it ->
            Row(Modifier.fillMaxWidth().clickable { onPick(it.id) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (it.on) c.accent else c.weak))
                Spacer(Modifier.width(14.dp))
                Text(it.label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                Spacer(Modifier.width(10.dp))
                Text(it.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
}

/** Mode chip text, only when it isn't the default (Build · Auto). */
fun modeChip(work: String, approval: String): String {
    val parts = buildList { if (work == "plan") add("PLAN"); if (approval == "autopilot") add("AUTOPILOT") }
    return parts.joinToString(" · ")
}

/**
 * One bar: + on the left, the text field (with a mode chip only when not default), mic, Send/Stop.
 */
@Composable
fun Composer(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, busy: Boolean, canSend: Boolean, plusOpen: Boolean,
             chip: String, agents: Int, listening: Boolean, speaking: Boolean,
             onPlus: () -> Unit, onMic: () -> Unit, onSend: () -> Unit, onStop: () -> Unit, onChip: () -> Unit = {}) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth().hairline(if (value.text.isNotEmpty() || busy) c.ink.copy(alpha = 0.35f) else c.hair)) {
            if (chip.isNotBlank() || agents > 0) Row(Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (chip.isNotBlank()) Box(Modifier.background(c.accent).clickable(onClick = onChip).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Label(chip, color = c.paper, size = 9.sp)
                }
                if (agents > 0) Label((if (chip.isNotBlank()) "   " else "") + "$agents AGENT" + (if (agents > 1) "S" else "") + " WORKING", color = c.accent, size = 9.sp)
            }
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.size(44.dp).background(if (plusOpen) c.ink else Color.Transparent).clickable(onClick = onPlus), contentAlignment = Alignment.Center) {
                    Text(if (plusOpen) "×" else "+", style = TextStyle(fontFamily = Type.Display, fontSize = 24.sp, color = if (plusOpen) c.paper else c.ink))
                }
                Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 12.dp)) {
                    if (value.text.isEmpty()) Text(if (listening) "Listening…" else "Message Blaze, or type /", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                    BasicTextField(value = value, onValueChange = onValue,
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink),
                        cursorBrush = SolidColor(c.accent), maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend && !busy) onSend() }),
                        modifier = Modifier.fillMaxWidth())
                }
                if (value.text.isEmpty() && !busy) {
                    val micOn = listening || speaking
                    Box(Modifier.size(44.dp).background(if (micOn) c.accent else Color.Transparent).clickable(onClick = onMic), contentAlignment = Alignment.Center) {
                        MicMark(if (micOn) c.paper else c.ink, speaking)
                    }
                }
                Spacer(Modifier.width(4.dp))
                val enabled = busy || canSend
                Box(Modifier.height(44.dp).background(if (busy) c.accent else if (enabled) c.ink else c.faint)
                    .clickable(enabled = enabled) { if (busy) onStop() else onSend() }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Label(if (busy) "Stop" else "Send", color = if (enabled) c.paper else c.weak, size = 11.sp)
                }
            }
        }
    }
}

@Composable
fun MicMark(color: Color, speaking: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 10f
        if (speaking) { drawRect(color, Offset(3 * u, 3 * u), Size(4 * u, 4 * u)); return@Canvas }
        drawRect(color, Offset(3.5f * u, 0f), Size(3 * u, 6 * u))
        drawArc(color, 0f, 180f, false, Offset(1.8f * u, 1.5f * u), Size(6.4f * u, 6.4f * u), style = Stroke(1.2f * u))
        drawRect(color, Offset(4.4f * u, 7.9f * u), Size(1.2f * u, 1.6f * u))
        drawRect(color, Offset(3f * u, 9f * u), Size(4f * u, 1f * u))
    }
}

// ---------------------------------------------------------------- projects

@Composable
fun ProjectsPane(projects: List<Project>, chats: List<ChatMeta>, busy: Set<String>, needsYou: Set<String>, openId: String,
                 onOpen: (String) -> Unit, onBack: () -> Unit, onCreate: (String) -> Unit, onChat: (String) -> Unit,
                 onNewChat: (String) -> Unit, onInstructions: (String, String) -> Unit, onDelete: (String) -> Unit) {
    val c = LocalBlaze.current
    val open = projects.firstOrNull { it.id == openId }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        if (open == null) {
            var name by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) {
                Label("Projects", modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(10.dp))
                Text("Group chats around one goal, with instructions Blaze follows in every chat inside it.", modifier = Modifier.padding(horizontal = 20.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).hairline(c.hair).padding(horizontal = 12.dp, vertical = 12.dp)) {
                        if (name.isEmpty()) Text("New project name", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                        BasicTextField(name, { name = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.height(46.dp).background(c.ink).clickable { onCreate(name); name = "" }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Label("+ Create", color = c.paper, size = 10.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
                projects.forEachIndexed { i, p ->
                    val working = p.chats.any { it in busy }
                    val needs = p.chats.any { it in needsYou }
                    Row(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.hairlineBottom(c.hair).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
                            Text(if (p.chats.size == 1) "1 chat" else "${p.chats.size} chats", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                        }
                        when {
                            needs -> StatusChip("Needs you", c.accent, filled = true)
                            working -> StatusChip("Working", c.accent, filled = false)
                        }
                    }
                }
                if (projects.isEmpty()) Text("No projects yet. Name one above to start.", modifier = Modifier.padding(20.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
            }
        } else {
            var ins by remember(open.id) { mutableStateOf(open.instructions) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                        Text("←", style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink))
                    }
                    Text(open.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, letterSpacing = (-0.02).em, color = c.ink))
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(46.dp).background(c.ink).clickable { onNewChat(open.id) },
                    contentAlignment = Alignment.Center) { Label("+  New chat in this project", color = c.paper, size = 10.sp) }
                Label("Instructions", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp))
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 72.dp).hairline(c.hair).padding(12.dp)) {
                    if (ins.isEmpty()) Text("What Blaze should know in every chat here", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                    BasicTextField(ins, { ins = it; onInstructions(open.id, it) }, cursorBrush = SolidColor(c.accent),
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
                }
                Label("Chats", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
                val list = open.chats.mapNotNull { id -> chats.firstOrNull { it.id == id } }
                list.forEach { ch -> ChatRow(ch, ch.id in busy, chip = if (ch.id in needsYou) "Needs you" else "") { onChat(ch.id) } }
                if (list.isEmpty()) Text("No chats yet.", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                Label("Delete project (chats stay)", color = c.sub, size = 9.sp, modifier = Modifier.padding(20.dp).clickable { onDelete(open.id) }.padding(4.dp))
            }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color, filled: Boolean) {
    val c = LocalBlaze.current
    Box(Modifier.background(if (filled) color else Color.Transparent).hairline(color).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Label(text, color = if (filled) c.paper else color, size = 9.sp)
    }
}
