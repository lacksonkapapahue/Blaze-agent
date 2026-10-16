package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
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
    /** v0.26.2: the most follow-up chips shown in the swipe row under a reply, and their longest label. */
    const val CHIPS_MAX = 5
    const val CHIP_CHARS = 42

    /** The chips for the one-row strip: trimmed, blank and repeated ones dropped, long ones cut with an ellipsis. Pure. */
    fun chipRow(list: List<String>): List<String> = list.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }.take(CHIPS_MAX).map { if (it.length > CHIP_CHARS) it.take(CHIP_CHARS - 1).trimEnd() + "\u2026" else it }

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

/**
 * v0.29.4: see-through bubbles. The fill is a light tint (so particles and the page show through) and a clear 1.2dp
 * outline marks where each bubble starts and ends. Yours is tinted with the accent blue, Blaze's is neutral.
 *
 * v0.29.5/0.29.6: nearly clear glass. The fills are a whisper of tint (no opaque backing anywhere, cards inside included)
 * and the particles drift across the thread. The text keeps a soft halo in the page colour ([halo]) so it stays readable.
 *
 * v0.30.5: theme-fitted glass instead of the blue outline (see below).
 */
object GlassBubble {
    /*
     * v0.30.5: glass that fits the theme (Luke: "give chat boxes the glass look as well fitted to the theme").
     * Dark: a translucent white-tinted glass with a faint light hairline. Light: a translucent white frost with a soft
     * darker hairline. Both are brighter at the top and keep the text halo, so particles still drift through. Your
     * bubbles carry a whisper of the accent; Blaze's are neutral. No blue outline.
     */
    private fun tint(c: BlazeColors, mine: Boolean): Color = if (mine) lerp(Color.White, c.accent, if (c.dark) 0.35f else 0.10f) else Color.White
    /** Top and bottom alpha of a bubble's glass. */
    private fun alphas(c: BlazeColors, mine: Boolean): Pair<Float, Float> = when {
        c.dark && mine -> 0.16f to 0.09f
        c.dark -> 0.10f to 0.05f
        mine -> 0.66f to 0.50f
        else -> 0.60f to 0.44f
    }
    /** The glass fill of a bubble (and the work card). */
    fun fill(c: BlazeColors, mine: Boolean): Brush = alphas(c, mine).let { (top, bottom) ->
        val t = tint(c, mine); Brush.verticalGradient(listOf(t.copy(alpha = top), t.copy(alpha = bottom)))
    }
    /** The hairline: a light rim catching the light at the top, the theme's soft edge below. */
    fun edge(c: BlazeColors): Brush = Brush.verticalGradient(listOf(hairTop(c), hairline(c)))
    fun hairline(c: BlazeColors): Color = if (c.dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.11f)
    fun hairTop(c: BlazeColors): Color = if (c.dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.95f)
    /** The most opaque the glass gets, per theme (GlassBubbleTest): frosted, never solid. */
    fun maxAlpha(dark: Boolean): Float = if (dark) 0.20f else 0.70f

    /** The glass's top colour (for fades inside answer cards, [LocalBubble]). */
    fun mineFill(c: BlazeColors): Color = alphas(c, true).let { tint(c, true).copy(alpha = it.first) }
    fun blazeFill(c: BlazeColors): Color = alphas(c, false).let { tint(c, false).copy(alpha = it.first) }
    /** Strips and chips in the thread (follow-ups): clear with a hairline instead of a grey slab. */
    fun stripFill(c: BlazeColors): Color = Color.Transparent
    fun stripEdge(c: BlazeColors): Color = c.ink.copy(alpha = if (c.dark) 0.22f else 0.16f)
    /** Code / table cards inside a bubble: a soft inset, still see-through. */
    fun cardFill(c: BlazeColors): Color = c.inset.copy(alpha = if (c.dark) 0.30f else 0.40f)
    /** A soft glow in the page colour behind every letter, so the words stay crisp with particles drifting across them. */
    fun halo(c: BlazeColors): Shadow = Shadow(color = c.paper.copy(alpha = if (c.dark) 0.85f else 0.95f), offset = Offset.Zero, blurRadius = 6f)
}

/** v0.30.5: a chat box's glass (bubble, work card): theme fill, then the hairline, in [shape]. */
fun Modifier.chatGlass(c: BlazeColors, mine: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    clip(shape).background(GlassBubble.fill(c, mine), shape).border(1.dp, GlassBubble.edge(c), shape)

/** Your message: a theme glass bubble on the right with a whisper of the accent (ink text). */
@Composable
fun UserBubble(text: String, quoted: String = "", pos: GroupPos = GroupPos.SINGLE, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val shape = Bubbles.shape(Side.ME, pos)
        Column(Modifier.padding(start = 48.dp).widthIn(max = 300.dp).chatGlass(c, mine = true, shape = shape)
            .padding(horizontal = 14.dp, vertical = 9.dp)) {
            if (quoted.isNotEmpty()) {
                Row(Modifier.padding(bottom = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(c.accent.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(8.dp))
                    Text(quoted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
                }
            }
            // v0.28.1: your own message renders its markdown too (## App, 1. **Offline mode**), never raw symbols.
            val shown = remember(text, c.ink) { Md.render(text, c.ink, c.ink.copy(alpha = 0.10f)) }
            SelectionContainer {
                Text(shown, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink, shadow = GlassBubble.halo(c)))
            }
        }
    }
}

/** Blaze's message: a soft bubble on the left. Cards inside it (code, tables) sit inset. */
@Composable
fun BlazeBubble(pos: GroupPos = GroupPos.SINGLE, modifier: Modifier = Modifier, fill: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    val bg = fill ?: GlassBubble.blazeFill(c)
    val shape = Bubbles.shape(Side.BLAZE, pos)
    Row(modifier.fillMaxWidth()) {
        // v0.30.5: theme glass; an error keeps its accent wash and edge.
        val look = if (fill != null) Modifier.clip(shape).background(fill).border(1.2.dp, c.accent.copy(alpha = 0.45f), shape)
            else Modifier.chatGlass(c, mine = false, shape = shape)
        Column(Modifier.padding(end = 28.dp).widthIn(max = 340.dp).then(look)
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
            Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body, shadow = GlassBubble.halo(c)))
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
    Row(modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background((fill ?: c.accentSoft).let { if (it == c.bubble) GlassBubble.stripFill(c) else it })
        .border(1.dp, if (fill == c.bubble) GlassBubble.stripEdge(c) else Color.Transparent, RoundedCornerShape(20.dp)).clickable(onClick = onClick)
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
fun BoxScope.UndoBar(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val c = LocalBlaze.current
    // v0.25.0: swipe it left, right or down to dismiss (it slides out that way).
    Row(modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).swipeAway(onDismiss).clip(RoundedCornerShape(24.dp)).background(c.ink)
        .padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.paper))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onUndo).semantics { contentDescription = "Undo clear" }
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            ButtonText("Undo", color = c.paper)
        }
    }
}

/** Send: an up arrow; Stop: a soft rounded square (v0.24.8). Drawn so they match the pixel-clean marks elsewhere. */
@Composable
fun SendMark(color: Color, stop: Boolean, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val u = w / 10f
        if (stop) { drawRoundRect(color, Offset(2.5f * u, 2.5f * u), Size(5 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(1.4f * u)); return@Canvas }
        drawLine(color, Offset(5 * u, 9 * u), Offset(5 * u, 1.5f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(1.6f * u, 4.8f * u), Offset(5 * u, 1.4f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(8.4f * u, 4.8f * u), Offset(5 * u, 1.4f * u), 1.8f * u, StrokeCap.Round)
    }
}

/**
 * v0.26.2: follow-up suggestions as one horizontal, swipeable row of compact chips under the reply
 * (was a stack of full-width pills). Each chip is one line; the row scrolls sideways when they don't fit.
 */
@Composable
fun FollowupRow(list: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    val chips = Bubbles.chipRow(list)
    if (chips.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 12.dp)) {
        items(chips.size) { i ->
            val s = chips[i]
            // v0.27.0: the chips fade up one after another under a new reply.
            Row(Modifier.appearIn(true, delayMs = 40 * i.coerceAtMost(4)).clip(RoundedCornerShape(18.dp)).background(c.accentSoft).clickable { onPick(s) }
                .semantics { contentDescription = "Suggestion: $s" }
                .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\u21b3", style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.accent))
                Spacer(Modifier.width(6.dp))
                Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, lineHeight = 18.sp, color = c.accent))
            }
        }
    }
}
