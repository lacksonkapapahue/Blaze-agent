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

/** The pointer as drawn by phone/Pointer.kt (same shape and colours), for Settings and screenshots. */
@Composable
fun PointerGlyph(paused: Boolean = false, ripple: Float = -1f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(44.dp)) {
        val d = density
        val tx = 6 * d; val ty = 6 * d
        if (ripple in 0f..1f) drawCircle(Color(0xFF1F4BD8).copy(alpha = 1 - ripple), radius = (4 + 14 * ripple) * d, center = Offset(tx, ty), style = Stroke(2.5f * d))
        val p = Path().apply {
            moveTo(tx, ty); lineTo(tx, ty + 22 * d); lineTo(tx + 6 * d, ty + 16.5f * d); lineTo(tx + 10 * d, ty + 25 * d)
            lineTo(tx + 14 * d, ty + 23 * d); lineTo(tx + 10 * d, ty + 14.5f * d); lineTo(tx + 17 * d, ty + 14.5f * d); close()
        }
        drawPath(p, if (paused) Color(0xFFE8743B) else Color(0xFF1F4BD8))
        drawPath(p, Color.White, style = Stroke(2 * d, join = StrokeJoin.Round))
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
