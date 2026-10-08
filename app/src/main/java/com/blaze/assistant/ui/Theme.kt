package com.blaze.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.R
import com.blaze.assistant.agent.Mood
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Design tokens lifted from blazeinchue.netlify.app (paper, ink, hairlines, blue accent, square edges). */
data class BlazeColors(
    val paper: Color,
    val ink: Color,
    val body: Color,
    val sub: Color,
    val weak: Color,
    val hair: Color,
    val faint: Color,
    val accent: Color,
    val dark: Boolean
)

val LightColors = BlazeColors(
    paper = Color(0xFFFCFCFC),
    ink = Color(0xFF111110),
    body = Color(0xD1111110),
    sub = Color(0x8F111110),
    weak = Color(0x57111110),
    hair = Color(0x1F111110),
    faint = Color(0x0F111110),
    accent = Color(0xFF1F4BD8),
    dark = false
)

val DarkColors = BlazeColors(
    paper = Color(0xFF26262A),
    ink = Color(0xFFF0F0EE),
    body = Color(0xD1F0F0EE),
    sub = Color(0x8FF0F0EE),
    weak = Color(0x57F0F0EE),
    hair = Color(0x26F0F0EE),
    faint = Color(0x12F0F0EE),
    accent = Color(0xFF6B8CFF),
    dark = true
)

val LocalBlaze = staticCompositionLocalOf { LightColors }

object Type {
    val Sans = FontFamily(
        Font(R.font.inter_400, FontWeight.Normal),
        Font(R.font.inter_500, FontWeight.Medium),
        Font(R.font.inter_600, FontWeight.SemiBold)
    )
    val Display = FontFamily(
        Font(R.font.inter_tight_400, FontWeight.Normal),
        Font(R.font.inter_tight_500, FontWeight.Medium),
        Font(R.font.inter_tight_600, FontWeight.SemiBold)
    )
    val Mono = FontFamily.Monospace
}

@Composable
fun BlazeTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalBlaze provides c, content = content)
}

/** Uppercase mono label, like the site's eyebrows and nav. */
@Composable
fun Label(text: String, color: Color = LocalBlaze.current.sub, size: TextUnit = 11.sp, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = TextStyle(fontFamily = Type.Mono, fontSize = size, letterSpacing = 0.14.em, color = color)
    )
}

/** 1px hairline on the bottom edge. */
fun Modifier.hairlineBottom(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

fun Modifier.hairlineTop(color: Color) = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
}

fun Modifier.hairline(color: Color) = border(1.dp, color)

private data class Dot(val x: Float, val y: Float, val r: Float, val vx: Float, val vy: Float)

/** Faint drifting particle dots (the site's button-hover particles), livelier while Blaze works. */
@Composable
fun ParticleField(mood: Mood, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    if (!com.blaze.assistant.agent.Settings.particles) return
    val energy by animateFloatAsState(if (mood == Mood.IDLE) 0f else 1f, tween(700), label = "e")
    // A slow clock instead of 60/120 fps: ~12 fps when idle, ~30 fps while working.
    var time by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val busy = mood != Mood.IDLE
    LaunchedEffect(busy) {
        var last = 0L
        val gap = if (busy) 33L else 85L
        while (true) {
            androidx.compose.runtime.withFrameMillis { now ->
                if (last == 0L) last = now
                if (now - last >= gap) { time = (time + (now - last) / 60_000f) % 1f; last = now }
            }
            if (!busy) delay(gap - 16)
        }
    }
    val dots = remember {
        val rnd = Random(11)
        List(44) {
            Dot(rnd.nextFloat(), rnd.nextFloat(), 0.8f + rnd.nextFloat() * 0.9f,
                (rnd.nextFloat() - 0.5f) * 2f, -0.6f - rnd.nextFloat() * 1.4f)
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val w = size.width; val h = size.height
        val speed = 1f + energy * 5f
        dots.forEachIndexed { i, d ->
            val px = ((d.x + d.vx * time * speed) % 1f + 1f) % 1f
            val py = ((d.y + d.vy * time * speed) % 1f + 1f) % 1f
            val a = 0.10f + 0.12f * energy + if (i % 7 == 0) 0.08f else 0f
            val col = if (i % 9 == 0 && energy > 0.1f) c.accent.copy(alpha = 0.5f * energy) else c.ink.copy(alpha = a)
            drawCircle(col, d.r * density, Offset(px * w, py * h))
        }
    }
}

private val bMask = listOf(
    "XXXX.", "X...X", "X...X", "XXXX.", "X...X", "X...X", "XXXX."
)

/**
 * Brand mark: an ink square with a pixel "B". While Blaze works the pixels scatter and
 * re-form like the site's pixel theme transition.
 */
@Composable
fun BrandMark(mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val busy = mood != Mood.IDLE
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy) {
        if (busy) while (true) { delay(110); tick++ } else tick = 0
    }
    Box(modifier.size(size).background(c.ink), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.6f)) {
            val px = this.size.width / 7f
            val ox = (this.size.width - px * 5) / 2f
            val oy = (this.size.height - px * 7) / 2f
            val rnd = Random(tick)
            for (r in 0 until 7) for (col in 0 until 5) {
                val on = bMask[r][col] == 'X'
                val show = if (busy) (if (on) rnd.nextFloat() > 0.25f else rnd.nextFloat() > 0.88f) else on
                if (show) {
                    val accentPx = busy && rnd.nextFloat() > 0.85f
                    drawRect(
                        if (accentPx) c.accent else c.paper,
                        Offset(ox + col * px, oy + r * px), Size(px * 0.92f, px * 0.92f)
                    )
                }
            }
        }
    }
}
