package com.blaze.assistant.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pointer as drawn by phone/Pointer.kt (v0.24.5: Blaze's flame mascot, gradient, eyes, glow, white edge; the tip is
 * the click point), from the same geometry ([com.blaze.assistant.phone.Mascot]), for screenshots. [ripple] 0..1 draws
 * the click ring and the squish; [pulse] is the gliding scale. [sizeDp] lets the icon shots draw it bigger.
 */
@Composable
fun PointerGlyph(paused: Boolean = false, ripple: Float = -1f, pulse: Float = 1f, modifier: Modifier = Modifier, scale: Float = 1f) {
    val P = com.blaze.assistant.phone.Pointer
    val M = com.blaze.assistant.phone.Mascot
    Canvas(modifier.size((P.TOUCH_DP * scale).dp)) {
        val d = density * scale
        val tx = P.TIP_X_DP * d; val ty = P.TIP_Y_DP * d
        val k = P.UNIT_DP * d
        val (sq1, sq2) = P.squish(ripple)
        val sx = sq1 * pulse; val sy = sq2 * pulse
        drawMascot(tx, ty, k, sx, sy, paused, d)
        drawCircle(Color.Black.copy(alpha = 0.3f), 2.1f * d, Offset(tx, ty))
        drawCircle(Color.White, 1.6f * d, Offset(tx, ty))
        if (ripple in 0f..1f) drawCircle(Color(0xFF1F4BD8).copy(alpha = 1 - ripple), radius = (4 + 12 * ripple) * d, center = Offset(tx, ty), style = Stroke(2.5f * d))
    }
}

/** The "Click '…'?" check after a manual double-tap (phone/Pointer.kt showCheck draws it with plain Views). */
@Composable
fun PointerCheck(line: String) {
    Row(Modifier.background(Color(0xFFFFFAF2)).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line, Modifier.padding(start = 4.dp, end = 10.dp), maxLines = 1, style = TextStyle(fontSize = 13.sp, color = Color(0xFF1D1A16)))
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text("Cancel", style = TextStyle(fontSize = 13.sp, color = Color(0xFF1F4BD8))) }
    }
}

/** The bar shown while Blaze is paused (phone/Pointer.kt draws it with plain Views). */
@Composable
fun PointerBar() {
    Row(Modifier.background(Color(0xFFFFFAF2)).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Paused · drag, double-tap to click", Modifier.padding(horizontal = 8.dp), style = TextStyle(fontSize = 12.sp, color = Color(0xFF6B645B)))
        Box(Modifier.background(Color(0xFF1F4BD8)).padding(horizontal = 14.dp, vertical = 8.dp)) { Text("Resume", style = TextStyle(fontSize = 13.sp, color = Color.White)) }
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { Text("Stop", style = TextStyle(fontSize = 13.sp, color = Color(0xFF1D1A16))) }
    }
}

/** Draws Blaze's mascot with its tip at (tx, ty), [k] px per unit, squish (sx, sy): glow, shadow, gradient body, edge, eyes. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMascot(tx: Float, ty: Float, k: Float, sx: Float = 1f, sy: Float = 1f,
                                                                paused: Boolean = false, d: Float = density, edge: Boolean = true) {
    val M = com.blaze.assistant.phone.Mascot
        fun at(u: FloatArray) = M.at(u, tx, ty, k, sx, sy).let { Offset(it[0], it[1]) }
        val body = Path().apply {
            M.walk(M.body, tx, ty, k, sx, sy, move = { x, y -> moveTo(x, y) }, cubic = { a1, b1, c1, d1, e1, f1 -> cubicTo(a1, b1, c1, d1, e1, f1) })
            close()
        }
        fun glow(u: FloatArray, r: Float, c: Int) {
            val col = Color(c); val o = at(u); val rr = r * k * maxOf(sx, sy)
            drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), center = o, radius = rr), rr, o)
        }
        glow(M.GLOW_BLUE_AT, M.GLOW_BLUE_R, M.GLOW_BLUE)
        glow(M.GLOW_ORANGE_AT, M.GLOW_ORANGE_R, M.GLOW_ORANGE)
        for (i in 3 downTo 1) drawPath(body, Color.Black.copy(alpha = 0.06f), style = Stroke(1.4f * i * d, join = StrokeJoin.Round))
        drawPath(body, Brush.verticalGradient(*M.STOPS.indices.map { M.STOPS[it] to Color(M.COLORS[it]) }.toTypedArray(),
            startY = at(floatArrayOf(0f, M.GRAD_TOP)).y, endY = at(floatArrayOf(0f, M.GRAD_BOTTOM)).y))
        val hc = Color(M.HIGHLIGHT)
        drawPath(body, Brush.radialGradient(listOf(hc, hc.copy(alpha = 0f)), center = at(M.HIGHLIGHT_AT), radius = M.HIGHLIGHT_R * k * maxOf(sx, sy)))
        if (edge || paused) drawPath(body, if (paused) Color(M.PAUSED) else Color.White.copy(alpha = 0.85f), style = Stroke((if (paused) 2.5f else 1.5f) * d, join = StrokeJoin.Round))
        for (e in M.EYES) {
            val c = at(e); val w = M.EYE_W * k * sx; val h = M.EYE_H * k * sy
            drawRoundRect(Color(M.EYE), Offset(c.x - w / 2, c.y - h / 2), androidx.compose.ui.geometry.Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(M.EYE_R * k, M.EYE_R * k))
        }
}

/** The mascot centred in a square of [size] (no tip marker), with an optional spinning ring around it (work banner). */
@Composable
fun MascotMark(size: androidx.compose.ui.unit.Dp, spin: Boolean = false, modifier: Modifier = Modifier) {
    val M = com.blaze.assistant.phone.Mascot
    val angle = if (spin) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ring")
        t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400,
            easing = androidx.compose.animation.core.LinearEasing)), label = "spin").value
    } else 0f
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val k = w * 0.62f / M.HEIGHT
        // Centre the whole mascot (tip to bottom) in the square.
        val ty = (w - M.HEIGHT * k) / 2f
        val tx = w / 2f + M.TIP[0] * k
        if (spin) {
            val sw = w * 0.07f
            drawCircle(Color(0x26F0F0EE), w / 2f - sw, style = Stroke(sw))
            drawArc(Brush.sweepGradient(listOf(Color(0x00FF9A3C), Color(0xFFFF9A3C), Color(0xFF4C7DFF))), angle, 120f, false,
                topLeft = Offset(sw, sw), size = androidx.compose.ui.geometry.Size(w - 2 * sw, w - 2 * sw),
                style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        drawMascot(tx, ty, k, edge = false, d = density * (w / 64.dp.toPx()).coerceIn(0.4f, 3f))
    }
}
