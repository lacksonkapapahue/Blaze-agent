package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * v0.24.0 messaging-style thread. Luke: "everything looks too uniform and boxed, I want it to look like a
 * messaging platform just with our classic theme."
 *
 * Same palette, fonts and mono labels; what changes is the shape: your messages are ink bubbles on the right,
 * Blaze's are soft bubbles on the left, consecutive messages from one side group with tight spacing and
 * asymmetric corners (the sharp corner points at the sender), and one quiet timestamp closes each group.
 * The grouping logic is pure (unit-tested in BubblesTest); the composables are stateless (ShotsTest draws them).
 */

/** Blaze's bubble: a soft fill a step off the paper (not a box, no outline). */
val BlazeColors.bubble: Color get() = if (dark) Color(0xFF35353B) else Color(0xFFEFEFEC)
/** A card inside a Blaze bubble (code, tables): lighter than the bubble so it reads as inset. */
val BlazeColors.inset: Color get() = if (dark) Color(0xFF2B2B30) else Color(0xFFFAFAF8)

/** The fill behind the content being drawn, so fades inside answer cards match it (null = the paper). */
val LocalBubble = staticCompositionLocalOf<Color?> { null }

enum class Side { ME, BLAZE }

/** Where a message sits in a run of messages from the same side. */
enum class GroupPos {
    SINGLE, FIRST, MIDDLE, LAST;
    val starts: Boolean get() = this == SINGLE || this == FIRST
    val ends: Boolean get() = this == SINGLE || this == LAST
}

object Bubbles {
    /** A pause this long between two messages from one side starts a new group (and a new timestamp). */
    const val GAP_MS = 10 * 60_000L

    /** Group positions for a thread, given each item's side and time (0 = unknown time, never splits). */
    fun positions(sides: List<Side>, ats: List<Long>, gapMs: Long = GAP_MS): List<GroupPos> {
        val n = sides.size
        fun joins(i: Int): Boolean { // item i continues item i-1's group
            if (i <= 0 || i >= n) return false
            if (sides[i] != sides[i - 1]) return false
            val a = ats.getOrElse(i - 1) { 0L }; val b = ats.getOrElse(i) { 0L }
            return a <= 0 || b <= 0 || b - a < gapMs
        }
        return List(n) { i ->
            val prev = joins(i); val next = joins(i + 1)
            when {
                !prev && !next -> GroupPos.SINGLE
                !prev -> GroupPos.FIRST
                next -> GroupPos.MIDDLE
                else -> GroupPos.LAST
            }
        }
    }

    /** Space above an item: tight inside a group, a breath between groups. */
    fun gapAbove(pos: GroupPos, first: Boolean): Dp = when {
        first -> 0.dp
        pos.starts -> 16.dp
        else -> 3.dp
    }

    const val BIG = 18
    const val JOIN = 6
    const val TAIL = 4

    /** Corner radii (topStart, topEnd, bottomEnd, bottomStart) in dp: the sender's side is sharp where messages join. */
    fun corners(side: Side, pos: GroupPos): List<Int> {
        val outerTop = if (pos.starts) TAIL else JOIN
        val outerBottom = if (pos.ends) BIG else JOIN
        return if (side == Side.ME) listOf(BIG, outerTop, outerBottom, BIG) else listOf(outerTop, BIG, BIG, outerBottom)
    }

    fun shape(side: Side, pos: GroupPos): RoundedCornerShape = corners(side, pos).let { (ts, te, be, bs) ->
        RoundedCornerShape(topStart = ts.dp, topEnd = te.dp, bottomEnd = be.dp, bottomStart = bs.dp)
    }

    /** "14:02" today, "Yesterday 14:02", "Mon 14:02" this week, else "12 Oct 14:02". "" when unknown. */
    fun time(at: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        if (at <= 0) return ""
        val hm = SimpleDateFormat("HH:mm", locale).format(Date(at))
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        fun dayNo(x: Calendar) = x.get(Calendar.YEAR) * 400 + x.get(Calendar.DAY_OF_YEAR)
        val days = dayNo(n) - dayNo(c)
        return when {
            days <= 0 -> hm
            days == 1 -> "Yesterday $hm"
            days < 7 -> SimpleDateFormat("EEE", locale).format(Date(at)) + " " + hm
            else -> SimpleDateFormat("d MMM", locale).format(Date(at)) + " " + hm
        }
    }
}

/** Your message: an ink bubble on the right (paper text). */
@Composable
fun UserBubble(text: String, quoted: String = "", pos: GroupPos = GroupPos.SINGLE, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(Modifier.padding(start = 48.dp).widthIn(max = 300.dp).clip(Bubbles.shape(Side.ME, pos)).background(c.ink)
            .padding(horizontal = 14.dp, vertical = 9.dp)) {
            if (quoted.isNotEmpty()) {
                Row(Modifier.padding(bottom = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(c.paper.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(8.dp))
                    Text(quoted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.paper.copy(alpha = 0.7f)))
                }
            }
            SelectionContainer {
                Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.paper))
            }
        }
    }
}

/** Blaze's message: a soft bubble on the left. Cards inside it (code, tables) sit inset. */
@Composable
fun BlazeBubble(pos: GroupPos = GroupPos.SINGLE, modifier: Modifier = Modifier, fill: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    val bg = fill ?: c.bubble
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.padding(end = 28.dp).widthIn(max = 340.dp).clip(Bubbles.shape(Side.BLAZE, pos)).background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            CompositionLocalProvider(LocalBubble provides bg) { content() }
        }
    }
}

/** A Blaze reply drawn in its bubble (screenshots and the thread use the same one). */
@Composable
fun BlazeReply(key: Long, text: String, pos: GroupPos = GroupPos.SINGLE) {
    BlazeBubble(pos) { AnswerBody(key, text) }
}

/** An error, in Blaze's bubble with an accent wash instead of a red box. */
@Composable
fun ErrorBubble(text: String, pos: GroupPos = GroupPos.SINGLE) {
    val c = LocalBlaze.current
    BlazeBubble(pos, fill = c.accentSoft) {
        Label("Error", color = c.accent, size = 9.sp)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
        }
    }
}

/** The quiet line under the last message of a group: time, then small text actions. */
@Composable
fun MetaLine(side: Side, time: String, actions: List<Pair<String, (() -> Unit)?>> = emptyList(), chip: String = "", chipHot: Boolean = false) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(top = 1.dp), horizontalArrangement = if (side == Side.ME) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically) {
        if (time.isNotBlank()) Text(time, style = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, color = c.weak),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        actions.forEachIndexed { i, a ->
            val act = a.second
            if (i > 0 || time.isNotBlank()) MetaDot()
            Text(a.first, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = if (act != null) c.sub else c.weak),
                modifier = Modifier.clickable(enabled = act != null) { act?.invoke() }.padding(horizontal = 6.dp, vertical = 8.dp))
        }
        if (chip.isNotBlank()) Text(chip, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Mono, fontSize = 9.sp, color = if (chipHot) c.accent else c.weak),
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
    }
}

@Composable
private fun MetaDot() {
    val c = LocalBlaze.current
    Box(Modifier.size(2.dp).clip(CircleShape).background(c.weak))
}

/** A tappable rounded pill (follow-ups, answers to a question, Try suggestions). */
@Composable
fun SoftPill(text: String, modifier: Modifier = Modifier, lead: String = "", fill: Color? = null, ink: Color? = null, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background(fill ?: c.accentSoft).clickable(onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lead.isNotBlank()) {
            Text(lead, style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = ink ?: c.accent))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, modifier = Modifier.weight(1f, fill = false), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 19.sp, color = ink ?: c.accent))
    }
}

/** The floating "Chat cleared · Undo" bar (no dialog: /clear only hides the screen). */
@Composable
fun BoxScope.UndoBar(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Row(modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp)).background(c.ink)
        .padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.paper))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onUndo).semantics { contentDescription = "Undo clear" }
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            Label("Undo", color = c.paper, size = 11.sp)
        }
    }
}

/** Send: an up arrow; Stop: a square. Drawn so they match the pixel-clean marks elsewhere. */
@Composable
fun SendMark(color: Color, stop: Boolean, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val u = w / 10f
        if (stop) { drawRect(color, Offset(2.5f * u, 2.5f * u), Size(5 * u, 5 * u)); return@Canvas }
        drawLine(color, Offset(5 * u, 9 * u), Offset(5 * u, 1.5f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(1.6f * u, 4.8f * u), Offset(5 * u, 1.4f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(8.4f * u, 4.8f * u), Offset(5 * u, 1.4f * u), 1.8f * u, StrokeCap.Round)
    }
}
