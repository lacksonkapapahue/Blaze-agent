package com.blaze.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.media.ImageJobs
import com.blaze.assistant.media.ImageSlot

/**
 * v0.30.8: the card a picture grows in while Blaze makes it (ChatGPT-style): a soft, drifting colour canvas at the
 * picture's aspect ratio that slowly sharpens ([ImageSlot.reveal] / [ImageSlot.blurDp]) under a light sweep, with a
 * quiet "Creating image" label. The finished picture replaces it in place (MediaStrip). A failed one is a short line
 * with Try again. With Remove animations on nothing moves: a still, soft canvas and the label.
 */
@Composable
fun ImageSlotCard(slot: ImageSlot.Slot, onRetry: () -> Unit) {
    val c = LocalBlaze.current
    val shape = BlazeShapes.medium
    if (slot.state == ImageSlot.State.FAILED) {
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp).widthIn(max = 330.dp).chatGlass(c, mine = false, shape = shape)
            .clickable(onClick = onRetry).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = ImageSlot.FAILED_TEXT + " Try again." },
            verticalAlignment = Alignment.CenterVertically) {
            Text(ImageSlot.FAILED_TEXT, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body, shadow = GlassBubble.halo(c)))
            Spacer(Modifier.width(12.dp))
            ActionText("Try again", color = c.accent, size = 11.sp)
        }
        return
    }

    val motion = MotionTokens.enabled
    val began = remember(slot.job) { ImageJobs.startedAt(slot.job) ?: System.currentTimeMillis() }
    var reveal by remember(slot.job) { mutableStateOf(ImageSlot.reveal(System.currentTimeMillis() - began)) }
    LaunchedEffect(slot.job, motion) {
        while (motion) {
            reveal = ImageSlot.reveal(System.currentTimeMillis() - began)
            kotlinx.coroutines.delay(160)
        }
    }
    val tr = rememberInfiniteTransition(label = "imageSlot")
    val driftA by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse), label = "driftA")
    val pulseA by tr.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "pulse")
    val drift = if (motion) driftA else 0.5f
    val pulse = if (motion) pulseA else 1f

    val base = if (c.dark) Color(0xFF1A1C21) else Color(0xFFECEDF1)
    val tones = listOf(BlazeBrush.blue, BlazeBrush.orange, c.accent)
    val strength = if (c.dark) 0.55f else 0.42f

    Box(Modifier.padding(top = 8.dp, bottom = 4.dp).widthIn(max = 330.dp).fillMaxWidth().aspectRatio(slot.aspect)
        .clip(shape).background(base, shape).border(1.dp, GlassBubble.edge(c), shape)
        .semantics { contentDescription = "Creating image" }) {
        // The canvas: three soft colour fields drifting; the blur eases off as it "develops" (blur needs Android 12+,
        // older phones keep the already-soft gradients).
        Canvas(Modifier.fillMaxSize().blur(ImageSlot.blurDp(reveal).dp, BlurredEdgeTreatment.Rectangle)) {
            val w = size.width; val h = size.height; val r = maxOf(w, h)
            val centers = listOf(
                Offset(w * (0.25f + 0.25f * drift), h * (0.30f + 0.15f * drift)),
                Offset(w * (0.80f - 0.30f * drift), h * (0.65f - 0.10f * drift)),
                Offset(w * (0.45f + 0.10f * drift), h * (0.90f - 0.35f * drift)),
            )
            centers.forEachIndexed { i, o ->
                val rad = r * (0.55f + 0.12f * i + 0.10f * reveal)
                drawCircle(Brush.radialGradient(listOf(tones[i].copy(alpha = strength * (0.75f + 0.25f * reveal)), Color.Transparent), o, rad), rad, o)
            }
        }
        // A light sweep across, like the canvas being worked on (still when animations are off).
        Box(Modifier.fillMaxSize().shimmer(Color.Transparent, Color.White.copy(alpha = if (c.dark) 0.08f else 0.30f), shape))
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.55f * pulse)))
            Spacer(Modifier.width(7.dp))
            Text("Creating image", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.ink.copy(alpha = 0.70f), shadow = GlassBubble.halo(c)))
        }
    }
}
