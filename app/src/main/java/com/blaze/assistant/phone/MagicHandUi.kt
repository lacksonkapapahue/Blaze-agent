package com.blaze.assistant.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.browser.PointerTile
import com.blaze.assistant.browser.PointerTiles
import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Kind
import com.blaze.assistant.phone.MagicHandState.DockButton
import com.blaze.assistant.phone.MagicHandState.GrabAction
import com.blaze.assistant.phone.MagicHandState.Panel
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.Md
import com.blaze.assistant.ui.PageHead
import com.blaze.assistant.ui.raised
import com.blaze.assistant.ui.Type

/**
 * Magic Hand's dock (v0.24.7): one rounded dark Blaze card at the bottom of the screen with the mascot, a status
 * line, the menu (the v0.24.6 pointer tiles, through MagicHandMenu's mapper), the answer, the confirm card, grabbed
 * text, the teach review, and Close. Everything renders from [MagicHandState.Ui], so ShotsTest draws it as is.
 */
class DockCalls(
    val onButton: (DockButton) -> Unit = {},
    val onMenu: (Action) -> Unit = {},
    val onConfirm: (Boolean) -> Unit = {},
    val onGrab: (GrabAction) -> Unit = {},
    val onBack: () -> Unit = {},
    val onTeachName: (String) -> Unit = {},
    val onTeachUndo: () -> Unit = {},
    val onTeachSave: () -> Unit = {},
    val onTeachDiscard: () -> Unit = {},
    val onTeachStart: () -> Unit = {},
)

object MagicHandIcons {
    fun of(a: Action): ImageVector = when (a) {
        Action.ASK -> Icons.Rounded.AutoAwesome
        Action.COPY -> Icons.Rounded.ContentCopy
        Action.EXPLAIN -> Icons.Rounded.Lightbulb
        Action.TRANSLATE -> Icons.Rounded.Translate
        Action.SHARE -> Icons.Rounded.Share
        Action.REMIND -> Icons.Rounded.Notifications
        Action.SEARCH -> Icons.Rounded.Search
        Action.OPEN_LINK -> Icons.Rounded.OpenInBrowser
        Action.COPY_LINK -> Icons.Rounded.Link
        Action.SAVE_IMAGE -> Icons.Rounded.SaveAlt
        Action.DESCRIBE -> Icons.Rounded.Image
        Action.CARRY, Action.DROP -> Icons.Rounded.PanTool
        Action.WATCH -> Icons.Rounded.Visibility
        Action.DROP_IN_CHAT -> Icons.Rounded.Forum
    }
    fun of(g: GrabAction): ImageVector = when (g) {
        GrabAction.COPY -> Icons.Rounded.ContentCopy
        GrabAction.TRANSLATE -> Icons.Rounded.Translate
        GrabAction.SEARCH -> Icons.Rounded.Search
        GrabAction.SAVE -> Icons.Rounded.SaveAlt
        GrabAction.CARRY -> Icons.Rounded.PanTool
    }
    fun of(k: Kind): ImageVector = when (k) {
        Kind.LINK -> Icons.Rounded.Link
        Kind.IMAGE -> Icons.Rounded.Image
        Kind.VALUE -> Icons.Rounded.Visibility
        else -> Icons.Rounded.TextFields
    }
}

private val sans = TextStyle(fontFamily = Type.Sans, color = Glass.text)

@Composable
fun MagicHandDock(u: MagicHandState.Ui, calls: DockCalls, widthDp: Float = 340f) {
    val card = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
        .shadow(20.dp, card, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(card).background(Glass.surface).border(1.dp, Glass.border, card)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MascotGlyph(34.dp, pulse = u.panel == Panel.ASKING)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (u.mode == MagicHandLogic.Mode.TEACH) "Teaching" else "Magic Hand", style = sans.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                    if (u.private) { Spacer(Modifier.width(6.dp)); Chip("Private", Glass.raised2, Glass.sub) }
                    if (u.carrying) { Spacer(Modifier.width(6.dp)); Chip("\u270b " + MagicHandLogic.preview(u.carried, 16), Glass.orange.copy(alpha = 0.18f), Glass.orange) }
                }
                Text(MagicHandState.statusLine(u), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = if (u.note.isNotBlank()) Glass.sub else Glass.cyan))
            }
            MagicHandState.dockButtons(u).forEach { b ->
                Spacer(Modifier.width(4.dp))
                GlassIconButton(when (b) {
                    DockButton.TEACH -> Icons.Rounded.School; DockButton.DONE -> Icons.Rounded.Check
                    DockButton.DROP_CHAT -> Icons.Rounded.Forum; DockButton.CLOSE -> Icons.Rounded.Close
                }, b.desc, size = 36.dp, bg = if (b == DockButton.DONE) Glass.blue else Glass.raised) { calls.onButton(b) }
            }
        }
        when (u.panel) {
            Panel.STATUS -> Unit
            Panel.MENU -> Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                MagicHandMenuCard(u, widthDp - 36f, calls.onMenu)
            }
            Panel.ASKING -> Line("Asking Blaze about \u201c${MagicHandLogic.preview(u.question.ifBlank { u.pointing }, 40)}\u201d\u2026")
            Panel.ANSWER -> {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SelectionContainer {
                        Text(Md.render(u.answer, Glass.cyan, Glass.raised2), style = sans.copy(fontSize = 14.5.sp, lineHeight = 21.sp))
                    }
                }
                Buttons(listOf("Done" to true)) { calls.onBack() }
            }
            Panel.CONFIRM -> u.confirm?.let { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Glass.raised).padding(12.dp)) {
                    Text(c.line, style = sans.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(4.dp))
                    Text("Magic Hand never acts without your yes.", style = sans.copy(fontSize = 12.sp, color = Glass.sub))
                }
                Buttons(listOf(c.no to false, c.yes to true)) { calls.onConfirm(it == 1) }
            }
            Panel.GRAB -> GrabCard(u, calls)
            Panel.TEACH_INTRO -> {
                Text(u.intro, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = sans.copy(fontSize = 14.sp, lineHeight = 20.sp))
                Buttons(listOf("Cancel" to false, "Start" to true)) { if (it == 1) calls.onTeachStart() else calls.onBack() }
            }
            Panel.TEACH -> {
                if (u.teachSteps.isEmpty()) Line("No steps yet.")
                else Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    u.teachSteps.takeLast(6).forEachIndexed { i, s -> StepLine(u.teachSteps.size - minOf(6, u.teachSteps.size) + i + 1, s) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                    Pill("Undo last step", false, enabled = u.teachSteps.isNotEmpty()) { calls.onTeachUndo() }
                    Spacer(Modifier.width(8.dp))
                    Pill("Cancel", false) { calls.onTeachDiscard() }
                    Spacer(Modifier.width(8.dp))
                    Pill("Done", true) { calls.onButton(DockButton.DONE) }
                }
            }
            Panel.TEACH_REVIEW -> TeachReviewCard(u, calls)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun MagicHandMenuCard(u: MagicHandState.Ui, widthDp: Float, onPick: (Action) -> Unit) =
    PointerTiles(MagicHandIcons.of(u.kind), MagicHandLogic.preview(u.pointing.ifBlank { "This part of the screen" }, 40),
        u.menu.map { a -> PointerTile(a.label, a.label, MagicHandIcons.of(a), a == Action.ASK || a == Action.DROP) { onPick(a) } }, widthDp)

@Composable
private fun GrabCard(u: MagicHandState.Ui, calls: DockCalls) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Glass.raised).padding(12.dp)) {
        Text("Grabbed" + if (u.grabSource.isNotBlank()) " \u00b7 ${u.grabSource}" else "", style = sans.copy(fontSize = 11.5.sp, color = Glass.orange, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(4.dp))
        Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(u.grabbed, style = sans.copy(fontSize = 14.sp, lineHeight = 20.sp)) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        MagicHandState.grabActions(u.private).forEach { g ->
            Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = g.label) { calls.onGrab(g) }.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (g == GrabAction.COPY) Glass.blue else Glass.raised2), contentAlignment = Alignment.Center) {
                    Icon(MagicHandIcons.of(g), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(g.label, style = sans.copy(fontSize = 10.5.sp))
            }
        }
    }
}

@Composable
private fun TeachReviewCard(u: MagicHandState.Ui, calls: DockCalls) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Glass.raised).padding(12.dp)) {
        Text("Save these ${u.teachSteps.size} steps as a workflow?", style = sans.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().clip(Glass.pill).background(Glass.raised2).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (u.teachName.isEmpty()) Text("Name it", style = sans.copy(fontSize = 14.sp, color = Glass.weak))
            BasicTextField(u.teachName, calls.onTeachName, singleLine = true, textStyle = sans.copy(fontSize = 14.sp),
                cursorBrush = SolidColor(Glass.blue), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
            u.teachSteps.forEachIndexed { i, s -> StepLine(i + 1, s) }
        }
        Spacer(Modifier.height(4.dp))
        Text("Saved as not tested yet. Blaze follows it once with you before trusting it.", style = sans.copy(fontSize = 11.5.sp, color = Glass.sub))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.Rounded.Delete, "Discard", size = 40.dp, bg = Glass.raised2) { calls.onTeachDiscard() }
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Rounded.Undo, "Remove the last step", size = 40.dp, bg = Glass.raised2, enabled = u.teachSteps.isNotEmpty()) { calls.onTeachUndo() }
        Spacer(Modifier.weight(1f))
        Pill("Save workflow", true, enabled = u.teachSteps.isNotEmpty()) { calls.onTeachSave() }
    }
}

@Composable
private fun StepLine(n: Int, s: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
            Text("$n", style = sans.copy(fontSize = 10.5.sp, color = Glass.orange))
        }
        Spacer(Modifier.width(8.dp))
        Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis, style = sans.copy(fontSize = 12.5.sp, lineHeight = 17.sp))
    }
}

@Composable
private fun Line(s: String) = Text(s, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = sans.copy(fontSize = 13.sp, color = Glass.sub))

@Composable
private fun Chip(s: String, bg: Color, fg: Color) =
    Text(s, maxLines = 1, modifier = Modifier.clip(Glass.pill).background(bg).padding(horizontal = 8.dp, vertical = 2.dp), style = sans.copy(fontSize = 10.5.sp, color = fg))

@Composable
private fun Pill(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    Box(Modifier.height(40.dp).clip(Glass.pill).background(if (!enabled) Glass.raised else if (primary) Glass.blue else Glass.raised2)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, style = sans.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (enabled) Color.White else Glass.weak))
    }

@Composable
private fun Buttons(labels: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        labels.forEachIndexed { i, (l, primary) ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            Pill(l, primary) { onPick(i) }
        }
    }
}

/** The hand itself: Blaze's mascot with a ring that lights orange while something is under its tip. */
@Composable
fun MagicHandGlyph(sizeDp: Int, lit: Boolean, carrying: Boolean) {
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        if (lit || carrying) Box(Modifier.size((sizeDp - 6).dp).clip(CircleShape).border(2.dp, if (carrying) Glass.orange else Glass.blue, CircleShape))
        MascotGlyph((sizeDp - 10).dp, pulse = false)
    }
}

/** Settings › Advanced › Watches: what Magic Hand is watching, newest first, each removable. */
@Composable
fun MagicHandWatchesPage(preview: List<MagicHandWatch.Watch>? = null, now: Long = System.currentTimeMillis()) {
    val ctx = LocalContext.current
    val live by MagicHandWatches.list.collectAsState()
    if (preview == null) androidx.compose.runtime.LaunchedEffect(Unit) { MagicHandWatches.load(ctx) }
    val list = (preview ?: live).sortedByDescending { it.created }
    val c = LocalBlaze.current
    PageHead("Magic Hand", "Watches", "Values you held the hand on. Blaze re-reads each one about once an hour while its app is open and tells you when it changes.")
    if (list.isEmpty()) EmptyState("Nothing watched yet", "Summon Magic Hand from the assistant panel, then hold it on a price, score or status and tap Watch.")
    else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised)) {
        list.forEachIndexed { i, w ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.label.ifBlank { w.value }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text(MagicHandWatchText.sub(w, now), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Stop watching") { MagicHandWatches.remove(ctx, w.id) },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Stop watching ${w.label}", tint = c.sub, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Plain lines for the watches page (pure, tested). */
object MagicHandWatchText {
    fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }
    fun sub(w: MagicHandWatch.Watch, now: Long): String = buildString {
        append(w.app.ifBlank { w.pkg }).append(" \u00b7 ").append(w.value)
        if (w.previous.isNotBlank()) append(" (was ").append(w.previous).append(")")
        append(" \u00b7 ").append(if (w.lastChecked > 0) "checked " + ago(now - w.lastChecked) else "not checked yet")
    }
}
