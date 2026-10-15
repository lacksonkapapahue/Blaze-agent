package com.blaze.assistant.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * v0.29.3: a web page inside a floating pane of glass, like a small monitor sitting on the Home board. The page scrolls and
 * its links open inside the pane. Tapping outside does nothing (only the × closes it, or Back). Sliding on the glass
 * around the page tilts the pane a little in that direction, and it springs back when you let go.
 */
object GlassTilt {
    const val MAX_Y = 14f   // degrees, left/right
    const val MAX_X = 10f   // degrees, up/down
    /** Drag offset (px) → tilt degrees, clamped so it never flips. [span] = the pane's size along that axis. */
    fun tilt(drag: Float, span: Float, max: Float): Float = if (span <= 0f) 0f else (drag / span * max * 2.2f).coerceIn(-max, max)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlassWindow(startUrl: String, onClose: () -> Unit, onOpenOutside: (String) -> Unit) {
    val c = LocalBlaze.current
    val g = LocalGlass.current ?: if (c.dark) HomeGlass.dark else HomeGlass.light
    val scope = rememberCoroutineScope()
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    val appear = remember { Animatable(0.92f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var title by remember { mutableStateOf(host(startUrl)) }
    var current by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    BackHandler { val w = web; if (w != null && w.canGoBack()) w.goBack() else onClose() }

    val none = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = if (c.dark) 0.35f else 0.18f))
        .clickable(interactionSource = none, indication = null) { /* tapping outside does nothing */ },
        contentAlignment = Alignment.Center) {
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val tiltInput = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDragEnd = { scope.launch { rx.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) }; scope.launch { ry.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) } },
                onDragCancel = { scope.launch { rx.animateTo(0f) }; scope.launch { ry.animateTo(0f) } },
            ) { ch, amount ->
                ch.consume(); dx += amount.x; dy += amount.y
                scope.launch { ry.snapTo(GlassTilt.tilt(dx, wPx, GlassTilt.MAX_Y)) }
                scope.launch { rx.snapTo(-GlassTilt.tilt(dy, hPx, GlassTilt.MAX_X)) }
            }
        }
        val paneShape = RoundedCornerShape(28.dp)
        Box(Modifier.fillMaxSize().then(tiltInput))   // slide anywhere off the pane to tilt it too
        Column(Modifier.fillMaxWidth(0.94f).height((maxHeight * 0.78f))
            .graphicsLayer {
                rotationX = rx.value; rotationY = ry.value; scaleX = appear.value; scaleY = appear.value
                cameraDistance = 14f * density; transformOrigin = TransformOrigin.Center
                shadowElevation = 24.dp.toPx(); shape = paneShape; clip = true
            }
            .background(g.fill).border(1.2.dp, g.edge, paneShape)
            .padding(10.dp)) {
            // Title bar: back, the site, open outside, ×.
            Row(Modifier.fillMaxWidth().then(tiltInput).padding(start = 4.dp, end = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { val w = web; if (w != null && w.canGoBack()) w.goBack() }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.ink))
                    Text(host(current), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
                }
                Spacer(Modifier.width(6.dp))
                GlassButton(Icons.Rounded.OpenInNew, "Open in browser") { onOpenOutside(current) }
                Spacer(Modifier.width(6.dp))
                GlassButton(Icons.Rounded.Close, "Close", strong = true, onClick = onClose)
            }
            // The "screen": a slightly inset, darker-edged panel holding the page.
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.18f), RoundedCornerShape(18.dp)).background(Color.White)) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                        settings.builtInZoomControls = true; settings.displayZoomControls = false
                        isVerticalScrollBarEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                                val u = req.url.toString()
                                return if (u.startsWith("http://") || u.startsWith("https://")) false else { onOpenOutside(u); true }
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { current = url; progress = 10 }
                            override fun onPageFinished(view: WebView, url: String) { current = url; progress = 100; view.title?.takeIf { it.isNotBlank() }?.let { title = it } }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                            override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }
                        }
                        loadUrl(startUrl)
                        web = this
                    }
                }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
                if (progress in 1..99) Box(Modifier.fillMaxWidth(progress / 100f).height(2.dp).background(c.accent))
            }
        }
    }
}

@Composable
private fun GlassButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, strong: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(36.dp).clip(CircleShape)
        .background(if (strong) Brush.linearGradient(listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.70f)))
            else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.22f))))
        .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
        .clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (strong) Color(0xFF111110) else c.ink, modifier = Modifier.size(if (strong) 18.dp else 17.dp))
    }
}

private fun host(u: String): String = try { android.net.Uri.parse(u).host.orEmpty().removePrefix("www.").ifBlank { u } } catch (_: Exception) { u }
