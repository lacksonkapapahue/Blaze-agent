package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * v0.19.0 visual parity: the small kit Blaze Desktop's pages are built from (frontend/dist/style.css:
 * .phead, .group, .rows, .pill, .btn, .empty), in the classic Blaze look: paper, ink, hairlines,
 * one blue accent, square edges, mono uppercase eyebrows.
 */

/** Desktop's --raised (cards, rows) and --hair2 (their borders), --accent-soft (on states). */
val BlazeColors.raised: Color get() = if (dark) Color(0xFF2E2E33) else Color(0xFFFFFFFF)
val BlazeColors.hair2: Color get() = if (dark) Color(0x2BF0F0EE) else Color(0x2E111110)
val BlazeColors.accentSoft: Color get() = accent.copy(alpha = if (dark) 0.16f else 0.09f)
val BlazeColors.onAccent: Color get() = if (dark) Color(0xFF0E0E10) else Color.White

/** Page header: accent eyebrow, 24sp display title, a lead line. */
@Composable
fun PageHead(eyebrow: String, title: String, lead: String = "", modifier: Modifier = Modifier, right: (@Composable () -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Label(eyebrow, color = c.accent, size = 10.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp,
                letterSpacing = (-0.02).em, color = c.ink))
            if (lead.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(lead, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, lineHeight = 20.sp, color = c.sub))
            }
        }
        if (right != null) { Spacer(Modifier.width(12.dp)); right() }
    }
}

/** Group header: mono label, optional action on the right, optional description under it. */
@Composable
fun GroupHead(title: String, desc: String = "", modifier: Modifier = Modifier, right: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, size = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
            right?.invoke(this)
        }
        if (desc.isNotBlank()) Text(desc, modifier = Modifier.padding(top = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.sub))
    }
}

/** Desktop's .rows: a raised box with hairlines between its rows. */
@Composable
fun Rows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().background(c.raised).hairline(c.hair2), content = content)
}

/**
 * One row: number, title (+ pill), sub line, trailing actions. [first] skips the top hairline.
 * Titles get one line, subs two, so nothing wraps into a wall on a 360dp phone.
 */
@Composable
fun KitRow(title: String, sub: String = "", n: Int = 0, first: Boolean = false, pill: String = "", pillOn: Boolean = false,
           dim: Boolean = false, mono: Boolean = false, onClick: (() -> Unit)? = null, lead: (@Composable () -> Unit)? = null,
           trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().let { if (first) it else it.hairlineTop(c.hair) }.let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .heightIn(min = 52.dp).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (n > 0) { Text("%02d".format(n), style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.weak)); Spacer(Modifier.width(12.dp)) }
        if (lead != null) { lead(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = if (mono) Type.Mono else Type.Sans, fontWeight = FontWeight.Medium,
                        fontSize = if (mono) 13.sp else 14.sp, color = if (dim) c.weak else c.ink))
                if (pill.isNotBlank()) { Spacer(Modifier.width(8.dp)); Tag(pill, on = pillOn) }
            }
            if (sub.isNotBlank()) Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.sub))
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); Row(verticalAlignment = Alignment.CenterVertically, content = trailing) }
    }
}

/** Small mono tag: accent-soft when [on], hairline otherwise. */
@Composable
fun Tag(text: String, on: Boolean = false) {
    val c = LocalBlaze.current
    Box(Modifier.background(if (on) c.accentSoft else c.faint).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text.uppercase(), maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 9.5.sp, letterSpacing = 0.08.em,
            color = if (on) c.accent else c.sub))
    }
}

enum class Btn { ACCENT, INK, LINE, GHOST }

/** Desktop's .btn: 36dp tall on a phone (touch), square, accent / ink / line / ghost. */
@Composable
fun KitButton(text: String, kind: Btn = Btn.LINE, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val bg = when (kind) { Btn.ACCENT -> c.accent; Btn.INK -> c.ink; Btn.LINE -> c.raised; Btn.GHOST -> Color.Transparent }
    val fg = when (kind) { Btn.ACCENT -> c.onAccent; Btn.INK -> c.paper; Btn.LINE -> c.ink; Btn.GHOST -> c.sub }
    Box(modifier.height(36.dp).widthIn(min = 64.dp).background(bg).let { if (kind == Btn.LINE) it.hairline(c.hair2) else it }
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = if (enabled) fg else fg.copy(alpha = 0.45f)))
    }
}

/** Desktop's .empty: a bold line and a quiet one, centred, with an optional action. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, mark: Boolean = true, action: String = "", onAction: () -> Unit = {}) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (mark) { DotMark(); Spacer(Modifier.height(14.dp)) }
        Text(title, textAlign = TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        Spacer(Modifier.height(4.dp))
        Text(body, textAlign = TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
        if (action.isNotBlank()) { Spacer(Modifier.height(14.dp)); KitButton(action, Btn.LINE, onClick = onAction) }
    }
}

/** The particle-dot mark empty states wear: a 3x3 grid of squares, the centre one blue. */
@Composable
fun DotMark(size: androidx.compose.ui.unit.Dp = 30.dp) {
    val c = LocalBlaze.current
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val g = this.size.width / 3f; val d = g * 0.56f; val o = (g - d) / 2f
        for (y in 0 until 3) for (x in 0 until 3) {
            val mid = x == 1 && y == 1
            drawRect(if (mid) c.accent else c.ink.copy(alpha = if ((x + y) % 2 == 1) 0.35f else 0.85f),
                androidx.compose.ui.geometry.Offset(x * g + o, y * g + o), androidx.compose.ui.geometry.Size(d, d))
        }
    }
}

/** Desktop's .msearch: hairline box that turns blue with a soft ring when focused. */
@Composable
fun KitSearch(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().let { if (focused) it.background(c.accentSoft).padding(2.dp) else it.padding(2.dp) }
        .background(c.raised).hairline(if (focused) c.accent else c.hair2).height(40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val col = if (focused) c.accent else c.weak
            drawCircle(col, this.size.width * 0.34f, androidx.compose.ui.geometry.Offset(this.size.width * 0.42f, this.size.height * 0.42f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
            drawLine(col, androidx.compose.ui.geometry.Offset(this.size.width * 0.68f, this.size.height * 0.68f),
                androidx.compose.ui.geometry.Offset(this.size.width, this.size.height), 1.4.dp.toPx())
        }
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, color = c.weak))
            BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(c.accent),
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, color = c.ink),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
        if (value.isNotEmpty()) Box(Modifier.size(28.dp).clickable { onChange("") }, contentAlignment = Alignment.Center) {
            Text("\u00d7", style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.sub))
        }
    }
}

/** Two- or three-way segmented control (Graph | List), ink when on. */
@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.hairline(c.hair2).background(c.raised).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (id, label) ->
            val on = id == value
            Box(Modifier.height(30.dp).background(if (on) c.ink else Color.Transparent).clickable { onPick(id) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp,
                    color = if (on) c.paper else c.sub))
            }
        }
    }
}

/** Square on/off switch (the classic one), usable outside Settings. */
@Composable
fun KitSwitch(on: Boolean, onToggle: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.clickable(onClick = onToggle).padding(4.dp)) {
        Box(Modifier.width(40.dp).height(22.dp).background(if (on) c.ink else c.faint).hairline(if (on) c.ink else c.hair2)) {
            Box(Modifier.padding(start = if (on) 20.dp else 2.dp, top = 2.dp).size(18.dp).background(if (on) c.paper else c.weak))
        }
    }
}
