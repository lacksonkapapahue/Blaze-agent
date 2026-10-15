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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * v0.29.3: a web page inside a floating pane of glass, like a small monitor sitting on the Home board. The page scrolls and
 * its links open inside the pane. Tapping outside does nothing (only the × closes it, or Back).
 *
 * v0.29.9: the pane moves only when you hold its glass rim or title bar (it leans a little as you carry it), and the
 * bottom corners resize it. The page in the middle is never a handle. Selecting words on the page shows the chat's
 * selection pill (Copy, Quote, Select all, Search, Ask Blaze, Translate, Define, Read aloud, Share).
 */
object GlassTilt {
    const val MAX_Y = 14f   // degrees, left/right
    const val MAX_X = 10f   // degrees, up/down
    /** Drag offset (px) → tilt degrees, clamped so it never flips. [span] = the pane's size along that axis. */
    fun tilt(drag: Float, span: Float, max: Float): Float = if (span <= 0f) 0f else (drag / span * max * 2.2f).coerceIn(-max, max)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlassWindow(startUrl: String, onClose: () -> Unit, onOpenOutside: (String) -> Unit,
                onAsk: ((String) -> Unit)? = null, onQuote: ((String) -> Unit)? = null) {
    val c = LocalBlaze.current
    val g = LocalGlass.current ?: if (c.dark) HomeGlass.dark else HomeGlass.light
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    val appear = remember { Animatable(0.92f) }
    var title by remember { mutableStateOf(host(startUrl)) }
    var current by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<PillWebView?>(null) }
    // v0.29.9: the page's text selection → Blaze's selection pill.
    var sel by remember { mutableStateOf<PageSelectMode?>(null) }
    var selTick by remember { mutableIntStateOf(0) }
    var selText by remember { mutableStateOf("") }
    var boxPos by remember { mutableStateOf(Offset.Zero) }
    // v0.29.9: where the pane sits (px in this box); null until the box is measured.
    var pane by remember { mutableStateOf<GlassPaneGrip.Pane?>(null) }
    var headerPx by remember { mutableFloatStateOf(0f) }
    var grabbing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    fun endSelection() { web?.clearSelection(); sel = null; selText = "" }
    BackHandler {
        val w = web
        when {
            sel != null -> endSelection()
            w != null && w.canGoBack() -> w.goBack()
            else -> onClose()
        }
    }

    val none = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxSize()
        .onGloballyPositioned { boxPos = it.positionInWindow() }
        .background(Color.Black.copy(alpha = if (c.dark) 0.35f else 0.18f))
        .clickable(interactionSource = none, indication = null) { if (sel != null) endSelection() /* else tapping outside does nothing */ }) {
        val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
        val minW = with(d) { GlassPaneGrip.MIN_W_DP.dp.toPx() }; val minH = with(d) { GlassPaneGrip.MIN_H_DP.dp.toPx() }
        val rimPx = with(d) { GlassPaneGrip.RIM_DP.dp.toPx() }; val cornerPx = with(d) { GlassPaneGrip.CORNER_DP.dp.toPx() }
        // First place: centred, 94% x 78%; refitted when the box changes size (rotation, keyboard).
        val p = remember(cw, ch, pane) { GlassPaneGrip.fit(pane ?: GlassPaneGrip.initial(cw, ch), cw, ch, minW, minH) }
        val paneShape = RoundedCornerShape(28.dp)
        // v0.29.9: only the glass rim and the title bar move the pane; the bottom corners resize it. The page in the middle
        // is never a handle, so scrolling, selecting and copying there never drag the window.
        val grip = Modifier.pointerInput(cw, ch, rimPx, cornerPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val zone = GlassPaneGrip.zone(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat(),
                    rimPx, rimPx + headerPx, cornerPx)
                if (!GlassPaneGrip.grabs(zone)) return@awaitEachGesture
                val start = GlassPaneGrip.fit(pane ?: GlassPaneGrip.initial(cw, ch), cw, ch, minW, minH)
                var tx = 0f; var ty = 0f
                val first = awaitTouchSlopOrCancellation(down.id) { ch0, over -> ch0.consume(); tx += over.x; ty += over.y } ?: return@awaitEachGesture
                grabbing = true
                if (sel != null) endSelection()
                pane = GlassPaneGrip.drag(start, zone, tx, ty, cw, ch, minW, minH)
                val ok = drag(first.id) { mv ->
                    val a = mv.positionChange(); mv.consume(); tx += a.x; ty += a.y
                    pane = GlassPaneGrip.drag(start, zone, tx, ty, cw, ch, minW, minH)
                    // A little lean in the direction it's carried (moving only), springing back on release.
                    if (zone == GlassPaneGrip.Zone.MOVE) {
                        scope.launch { ry.snapTo(GlassTilt.tilt(a.x * 6f, start.w, GlassTilt.MAX_Y * 0.4f)) }
                        scope.launch { rx.snapTo(-GlassTilt.tilt(a.y * 6f, start.h, GlassTilt.MAX_X * 0.4f)) }
                    }
                }
                grabbing = false
                scope.launch { rx.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) }
                scope.launch { ry.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) }
                if (!ok) pane = start
            }
        }
        Box(Modifier.offset { IntOffset(p.l.roundToInt(), p.t.roundToInt()) }
            .size(with(d) { p.w.toDp() }, with(d) { p.h.toDp() })
            .then(grip)   // before the tilt layer, so the grab zones never lean with the glass
            .graphicsLayer {
                rotationX = rx.value; rotationY = ry.value; scaleX = appear.value; scaleY = appear.value
                cameraDistance = 14f * density; transformOrigin = TransformOrigin.Center
                shadowElevation = (if (grabbing) 32 else 24).dp.toPx(); shape = paneShape; clip = true
            }
            .background(g.fill).border(if (grabbing) 1.8.dp else 1.2.dp, if (grabbing) c.accent.copy(alpha = 0.7f) else g.edge, paneShape)) {
            Column(Modifier.fillMaxSize().padding(start = GlassPaneGrip.RIM_DP.dp, end = GlassPaneGrip.RIM_DP.dp, top = GlassPaneGrip.RIM_DP.dp - 6.dp, bottom = GlassPaneGrip.RIM_DP.dp)) {
                // Title bar (a handle too): back, the site, open outside, ×.
                Row(Modifier.fillMaxWidth().onSizeChanged { headerPx = it.height.toFloat() - with(d) { 6.dp.toPx() } }.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
                    AndroidView(factory = { vctx ->
                        PillWebView(vctx) { m -> sel = m; selTick++; if (m == null) selText = "" else web?.selectedText { t -> if (sel === m) selText = t } }.apply {
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
                    }, onRelease = { it.clearSelection(); it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
                    if (progress in 1..99) Box(Modifier.fillMaxWidth(progress / 100f).height(2.dp).background(c.accent))
                }
            }
            // Grip marks: a short bar on the bottom rim (move) and corner ticks (resize).
            GripMarks(Modifier.matchParentSize(), c.ink.copy(alpha = if (grabbing) 0.55f else 0.28f), rimPx, cornerPx)
        }

        // v0.29.9: the chat's selection pill for words selected on the page.
        val m = sel
        if (m != null && !m.hidden && !grabbing) {
            val w = web
            if (w != null && selTick >= 0) {   // selTick: redraw when the selection moves
                val loc = IntArray(2).also { w.getLocationInWindow(it) }
                val r = m.contentRect()
                val rect = Rect(r.left + loc[0] - boxPos.x, r.top + loc[1] - boxPos.y, r.right + loc[0] - boxPos.x, r.bottom + loc[1] - boxPos.y)
                val acts = if (selText.isNotBlank()) SelectionActions.forText(selText, allowQuote = onQuote != null, allowSelectAll = m.has(android.R.id.selectAll))
                    else SelectionActions.forUnknown(SelectionActions.likelyShort(rect.width / d.density, rect.height / d.density),
                        allowQuote = onQuote != null, allowSelectAll = m.has(android.R.id.selectAll))
                SelectionPill(rect, acts, ink = Color(0xFF111114), paper = Color.White) { a ->
                    fun act(t0: String) {
                        val t = t0.trim()
                        endSelection()
                        if (t.isBlank()) return
                        when (SelectionActions.resolve(a, t)) {
                            SelectionActions.Action.QUOTE -> onQuote?.invoke(t)
                            // Search stays in the pane (Back returns to the page); a selected link opens there too.
                            SelectionActions.Action.SEARCH, SelectionActions.Action.OPEN_LINK -> web?.loadUrl(SelectionActions.browserUrl(t))
                            SelectionActions.Action.ASK -> onAsk?.invoke(SelectionActions.askPrompt(t)) ?: SelectionDo.openInBlazeBrowser(ctx, t)
                            SelectionActions.Action.TRANSLATE -> onAsk?.invoke(SelectionActions.translatePrompt(t, SelectionDo.phoneLanguage()))
                            SelectionActions.Action.DEFINE -> onAsk?.invoke(SelectionActions.definePrompt(t))
                            SelectionActions.Action.READ_ALOUD -> SelectionDo.readAloud(ctx, t)
                            SelectionActions.Action.SHARE -> SelectionDo.share(ctx, t)
                            else -> {}
                        }
                    }
                    when (a) {
                        SelectionActions.Action.COPY -> {
                            if (!m.click(android.R.id.copy)) copyText(ctx, selText)
                            endSelection()
                        }
                        SelectionActions.Action.SELECT_ALL -> { m.click(android.R.id.selectAll) }
                        else -> if (selText.isNotBlank()) act(selText) else w.selectedText { t ->
                            if (t.isNotBlank()) act(t)
                            else { // The page couldn't say (a frame): copy through the page, then read it back.
                                m.click(android.R.id.copy); act(clipText(ctx))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A short bar centred on the bottom rim and a small tick in each bottom corner, so the handles can be seen. */
@Composable
private fun GripMarks(modifier: Modifier, color: Color, rimPx: Float, cornerPx: Float) {
    Canvas(modifier) {
        val sw = 1.6.dp.toPx(); val y = size.height - rimPx / 2f
        val bar = 34.dp.toPx()
        drawLine(color, Offset((size.width - bar) / 2f, y), Offset((size.width + bar) / 2f, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        val k = cornerPx * 0.32f; val i = rimPx * 0.55f
        // bottom-right ◢ tick
        drawLine(color, Offset(size.width - i - k, size.height - i), Offset(size.width - i, size.height - i - k), sw, StrokeCap.Round)
        drawLine(color, Offset(size.width - i - k * 0.5f, size.height - i), Offset(size.width - i, size.height - i - k * 0.5f), sw, StrokeCap.Round)
        // bottom-left ◣ tick
        drawLine(color, Offset(i + k, size.height - i), Offset(i, size.height - i - k), sw, StrokeCap.Round)
        drawLine(color, Offset(i + k * 0.5f, size.height - i), Offset(i, size.height - i - k * 0.5f), sw, StrokeCap.Round)
    }
}

private fun copyText(ctx: android.content.Context, t: String) {
    if (t.isBlank()) return
    try {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Blaze", t))
    } catch (_: Exception) { }
}

private fun clipText(ctx: android.content.Context): String = try {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
} catch (_: Exception) { "" }

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
