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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.clip
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

/** Design tokens lifted from blazeinchue.netlify.app (paper, ink, hairlines, blue accent); v0.24.8: every edge rounded. */
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

/** Light/dark override; "system" follows the phone. Backed by Settings.themeMode. */
object ThemeMode {
    val mode = androidx.compose.runtime.mutableStateOf(try { com.blaze.assistant.agent.Settings.themeMode } catch (_: Exception) { "system" })
    fun set(m: String) { mode.value = m; com.blaze.assistant.agent.Settings.themeMode = m }
}

@Composable
fun BlazeTheme(content: @Composable () -> Unit) {
    val sys = isSystemInDarkTheme()
    val dark = when (ThemeMode.mode.value) { "dark" -> true; "light" -> false; else -> sys }
    val c = if (dark) DarkColors else LightColors
    // v0.24.5: Material's own colours mapped to Blaze, so no default purple / lavender can leak into anything Material draws.
    androidx.compose.material3.MaterialTheme(colorScheme = blazeScheme(dark), shapes = BlazeShapes.material) {
        CompositionLocalProvider(LocalBlaze provides c, content = content)
    }
}

/**
 * v0.24.8 "no square UI": the one set of corner radii every Blaze surface uses. Inputs and buttons are pills or [medium],
 * cards [medium], sheets and dialogs [large], tiny marks (checkboxes, tags inside rows) [small]. Nothing is square:
 * `ui/NoSquareTest` fails the build if RectangleShape or RoundedCornerShape(0 appears in ui/.
 */
object BlazeShapes {
    val xs = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    val pill = androidx.compose.foundation.shape.CircleShape
    /** Material's own shapes mapped to the same radii, so any Material piece that slips in is rounded too. */
    val material = androidx.compose.material3.Shapes(
        extraSmall = xs, small = small, medium = medium, large = large,
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
}

/** Brand gradient for primary buttons: the mascot's warm orange into its blue (v0.24.8). */
object BlazeBrush {
    val orange = Color(0xFFFF9A3C)
    val blue = Color(0xFF4C7DFF)
    val primary: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(orange, blue))
    val primarySoft: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(orange.copy(alpha = 0.18f), blue.copy(alpha = 0.18f)))
}

/** Material colour scheme from Blaze's tokens (v0.24.5): blue primary, orange secondary, Blaze surfaces; never purple. */
fun blazeScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    val blue = Color(0xFF4C7DFF); val orange = Color(0xFFFF9A3C)
    return if (dark) androidx.compose.material3.darkColorScheme(
        primary = blue, onPrimary = Color.White, primaryContainer = Color(0xFF1E2A4A), onPrimaryContainer = Color(0xFFDCE4FF),
        secondary = orange, onSecondary = Color(0xFF1A1206), secondaryContainer = Color(0xFF3A2814), onSecondaryContainer = Color(0xFFFFE2C8),
        tertiary = blue, onTertiary = Color.White, tertiaryContainer = Color(0xFF1E2A4A), onTertiaryContainer = Color(0xFFDCE4FF),
        background = DarkColors.paper, onBackground = DarkColors.ink, surface = Color(0xFF16171A), onSurface = DarkColors.ink,
        surfaceVariant = Color(0xFF2E2E33), onSurfaceVariant = DarkColors.sub, surfaceTint = Color.Transparent,
        inverseSurface = LightColors.paper, inverseOnSurface = LightColors.ink, inversePrimary = Color(0xFF1F4BD8),
        outline = Color(0x40F0F0EE), outlineVariant = Color(0x1FF0F0EE), scrim = Color.Black,
        surfaceBright = Color(0xFF2E2E33), surfaceDim = Color(0xFF111214), surfaceContainerLowest = Color(0xFF0F1012),
        surfaceContainerLow = Color(0xFF141518), surfaceContainer = Color(0xFF16171A), surfaceContainerHigh = Color(0xFF1C1D21),
        surfaceContainerHighest = Color(0xFF232428), error = Color(0xFFFF6B5E), onError = Color.White)
    else androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF1F4BD8), onPrimary = Color.White, primaryContainer = Color(0xFFE3E9FF), onPrimaryContainer = Color(0xFF0B1E5C),
        secondary = Color(0xFFE8743B), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE6D6), onSecondaryContainer = Color(0xFF4A2410),
        tertiary = Color(0xFF1F4BD8), onTertiary = Color.White, tertiaryContainer = Color(0xFFE3E9FF), onTertiaryContainer = Color(0xFF0B1E5C),
        background = LightColors.paper, onBackground = LightColors.ink, surface = Color(0xFFFFFFFF), onSurface = LightColors.ink,
        surfaceVariant = Color(0xFFF1F1F0), onSurfaceVariant = LightColors.sub, surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF16171A), inverseOnSurface = DarkColors.ink, inversePrimary = blue,
        outline = Color(0x40111110), outlineVariant = Color(0x1F111110), scrim = Color.Black,
        surfaceBright = Color.White, surfaceDim = Color(0xFFEDEDEC), surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainer = Color(0xFFF6F6F5), surfaceContainerHigh = Color(0xFFF1F1F0),
        surfaceContainerHighest = Color(0xFFECECEB), error = Color(0xFFD93A2B), onError = Color.White)
}

/** Sun/moon: tap flips light and dark, long-press goes back to following the phone. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThemeButton(modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.size(38.dp).hairline(c.hair, BlazeShapes.pill).combinedClickable(
        onClick = { ThemeMode.set(if (c.dark) "light" else "dark") },
        onLongClick = { ThemeMode.set("system"); android.widget.Toast.makeText(ctx, "Following your phone's theme", android.widget.Toast.LENGTH_SHORT).show() }
    ), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val r = size.minDimension / 2
            if (c.dark) {
                // Sun: disc and eight rays (tap for light).
                drawCircle(c.ink, r * 0.42f, center)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                    drawLine(c.ink, Offset(center.x + dx * r * 0.62f, center.y + dy * r * 0.62f), Offset(center.x + dx * r * 0.95f, center.y + dy * r * 0.95f), 1.6.dp.toPx())
                }
            } else {
                // Moon: a disc with a bite taken out (tap for dark).
                drawCircle(c.ink, r * 0.8f, center)
                drawCircle(c.paper, r * 0.66f, Offset(center.x + r * 0.42f, center.y - r * 0.3f))
            }
        }
    }
}

/** Uppercase mono label, like the site's eyebrows and nav. */
@Composable
fun Label(text: String, color: Color = LocalBlaze.current.sub, size: TextUnit = 11.sp, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text.uppercase(),
        modifier = modifier,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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

/**
 * 1dp outline that follows a rounded [shape] (v0.24.8: [BlazeShapes.medium] by default, never square) and clips what comes
 * after it (content, ripples) to the same shape. Give the background before it the same shape: `.background(x, shape)`.
 */
fun Modifier.hairline(color: Color, shape: androidx.compose.ui.graphics.Shape = BlazeShapes.medium) =
    clip(shape).border(1.dp, color, shape)

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

/**
 * Brand mark (v0.24.8): Blaze's mascot in a dark circle (it used to be a square with a pixel "B"). While Blaze works a
 * thin orange-to-blue ring spins around it.
 */
@Composable
fun BrandMark(mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    val busy = mood != Mood.IDLE
    Box(modifier.size(size).clip(BlazeShapes.pill).background(Color(com.blaze.assistant.phone.Mascot.ICON_BG), BlazeShapes.pill),
        contentAlignment = Alignment.Center) {
        if (busy) MascotMark(size, spin = true)
        else MascotMark(size, modifier = Modifier.requiredSize(size * 1.2f))
    }
}
