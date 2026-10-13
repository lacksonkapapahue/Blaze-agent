package com.blaze.assistant.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pointer as drawn by phone/Pointer.kt (v0.24.4: Blaze's spark blob with a white outline, soft shadow and a tip
 * marker), from the same geometry ([com.blaze.assistant.phone.SparkBlob]), for screenshots. [ripple] 0..1 draws the
 * click ring and the squish; [pulse] is the gliding scale.
 */
@Composable
fun PointerGlyph(paused: Boolean = false, ripple: Float = -1f, pulse: Float = 1f, modifier: Modifier = Modifier) {
    val P = com.blaze.assistant.phone.Pointer
    val B = com.blaze.assistant.phone.SparkBlob
    Canvas(modifier.size(P.TOUCH_DP.dp)) {
        val d = density
        val tx = P.TIP_DP * d; val ty = P.TIP_DP * d
        val cx = P.BLOB_CENTER_DP * d; val cy = P.BLOB_CENTER_DP * d
        val px = P.BLOB_DP * d
        val (sq1, sq2) = P.squish(ripple)
        val sx = sq1 * pulse; val sy = sq2 * pulse
        fun path(sh: com.blaze.assistant.phone.SparkBlob.Shape, ox: Float = 0f, oy: Float = 0f) = Path().apply {
            B.walk(sh, cx + ox, cy + oy, px, sx, sy, { x, y -> moveTo(x, y) }, { a1, b1, c1, d1, e1, f1 -> cubicTo(a1, b1, c1, d1, e1, f1) })
            close()
        }
        val accent = Color(0xFF1F4BD8)
        drawLine(Color.White, Offset(tx, ty), Offset(cx - px * 0.28f, cy - px * 0.28f), strokeWidth = 2 * d, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        // Soft shadow: a few offset, fading copies under the blob.
        for (i in 3 downTo 1) drawPath(path(B.blob, 0f, 0.6f * i * d), Color.Black.copy(alpha = 0.07f), style = Stroke(1.6f * i * d, join = StrokeJoin.Round))
        val blob = path(B.blob)
        drawPath(blob, Color(B.INK))
        drawPath(blob, Color.White, style = Stroke(2.5f * d, join = StrokeJoin.Round))
        drawPath(path(B.spark), Color(if (paused) B.SPARK_PAUSED else B.SPARK))
        drawCircle(Color.Black.copy(alpha = 0.18f), 5.2f * d, Offset(tx, ty + 0.5f * d))
        drawCircle(Color.White, 4.5f * d, Offset(tx, ty))
        drawCircle(if (paused) Color(B.SPARK_PAUSED) else accent, 2.8f * d, Offset(tx, ty))
        if (ripple in 0f..1f) drawCircle(accent.copy(alpha = 1 - ripple), radius = (5 + 12 * ripple) * d, center = Offset(tx, ty), style = Stroke(2.5f * d))
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
