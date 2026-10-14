package com.blaze.assistant.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blaze.assistant.desktop.DesktopPlan
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.desktop.LinuxDesktop
import com.blaze.assistant.desktop.Rfb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v0.25.1 Desktop view: one Start button for a real Linux desktop inside Blaze's Alpine, progress in plain words,
 * a Resume card if Android closed it, and a full-screen viewer (touch as mouse, keyboard, landscape, Stop).
 * [preview] draws a fixed state for screenshots.
 */
@Composable
fun LinuxDesktopPage(preview: LinuxDesktop.State? = null) {
    val ctx = LocalContext.current
    val c = LocalBlaze.current
    val scope = rememberCoroutineScope()
    val live by LinuxDesktop.state.collectAsState()
    val st = preview ?: live
    var viewing by remember { mutableStateOf(false) }
    val supported = preview != null || remember { LinuxDesktop.cpuOk() }
    val killed = preview == null && remember(st.phase) { LinuxDesktop.wasKilled() } || st.phase == Phase.KILLED
    // v0.25.5: the live desktop card asked for the full-screen view.
    val wantViewer by LinuxDesktop.viewerRequest.collectAsState()
    LaunchedEffect(wantViewer, st.phase) {
        if (preview == null && wantViewer) { if (st.phase == Phase.RUNNING) viewing = true; LinuxDesktop.viewerRequest.value = false }
    }

    PageHead("Desktop", "Linux desktop", "A real Linux desktop (XFCE) running inside Blaze's own Linux on this phone. Nothing leaves the phone; Blaze can work in it too, always after asking.")
    Spacer(Modifier.height(14.dp))
    if (!supported || st.phase == Phase.UNSUPPORTED) {
        BlazeCard { Text(DesktopPlan.UNSUPPORTED, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body)) }
        return
    }
    BlazeCard(accent = st.phase == Phase.RUNNING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(BlazeShapes.pill).background(when (st.phase) { Phase.RUNNING -> c.accent; Phase.ERROR, Phase.KILLED -> BlazeBrush.orange; else -> c.weak }))
            Spacer(Modifier.width(10.dp))
            Text(when {
                killed -> "Closed by Android"
                st.phase == Phase.IDLE || st.phase == Phase.STOPPED -> if (preview == null && !LinuxDesktop.installed()) "Not installed yet" else "Ready"
                else -> st.line.ifBlank { st.phase.name.lowercase().sentence() }
            }, style = TextStyle(fontFamily = Type.Display, fontSize = 16.sp, color = c.ink))
        }
        if (st.phase == Phase.INSTALLING || st.phase == Phase.STARTING) {
            Spacer(Modifier.height(10.dp))
            BrandProgress(st.pct ?: 50)
        }
        Spacer(Modifier.height(8.dp))
        Text(when {
            killed -> DesktopPlan.KILLED_NOTE
            st.phase == Phase.ERROR -> st.error
            st.phase == Phase.RUNNING -> "${DesktopPlan.WIDTH}\u00d7${DesktopPlan.HEIGHT}, 16-bit colour, only reachable from this phone."
            preview == null && LinuxDesktop.installed() -> "Light defaults for phones with 3\u20134 GB of memory. Stop it when you're done to free memory."
            else -> "First start installs it: " + DesktopPlan.sizeLine()
        }, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    }
    Spacer(Modifier.height(14.dp))
    val busy = st.phase == Phase.INSTALLING || st.phase == Phase.STARTING || st.phase == Phase.STOPPING
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.phase == Phase.RUNNING) {
            BlazeButton("Open full screen", Modifier.weight(1f)) { viewing = true }
            BlazeButton("Stop", kind = BtnKind.SECONDARY) { scope.launch { LinuxDesktop.stop() } }
        } else BlazeButton(if (killed) "Resume" else if (busy) "Working\u2026" else "Start", Modifier.weight(1f), enabled = !busy) {
            // v0.25.5: runs in Blaze's own scope, so leaving this page doesn't cancel a download.
            val job = LinuxDesktop.startAsync(ctx)
            scope.launch { if (job.await().startsWith("The desktop is running")) viewing = true }
        }
    }
    Spacer(Modifier.height(18.dp))
    GroupHead("How it works", "")
    Text("Tap to click, two fingers to right-click or scroll, pinch to zoom and drag to pan when zoomed. The keyboard button types; the arrow turns it sideways.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    if (viewing && st.phase == Phase.RUNNING && preview == null) DesktopViewer(onClose = { viewing = false })
}

/** Full-screen viewer. Closing it leaves the desktop running; Stop ends it. */
@Composable
fun DesktopViewer(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var frame by remember { mutableIntStateOf(0) }
    var drawn by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    val client = remember {
        Rfb.Client(LinuxDesktop.password, onFrame = { frame++ }, onClosed = { r -> error = r; if (r.isNotBlank()) LinuxDesktop.lost() })
    }
    val bmp = remember { mutableStateOf<Bitmap?>(null) }
    var landscape by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize(1, 1)) }
    DisposableEffect(Unit) {
        client.connect()
        val act = ctx as? Activity
        val before = act?.requestedOrientation
        onDispose {
            client.close()
            bmp.value?.recycle(); bmp.value = null
            if (act != null && before != null) act.requestedOrientation = before
        }
    }
    LaunchedEffect(landscape) {
        (ctx as? Activity)?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Copy the framebuffer into the bitmap after each update.
    LaunchedEffect(frame) {
        val w = client.width; val h = client.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val b = bmp.value?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { bmp.value?.recycle(); bmp.value = it }
        try { b.copyPixelsFromBuffer(java.nio.ShortBuffer.wrap(client.fb)) } catch (_: Exception) { }
        bmp.value = b
        drawn++
    }
    fun fit(): Float = if (client.width <= 0) 1f else minOf(box.width.toFloat() / client.width, box.height.toFloat() / client.height)
    fun toDesk(p: Offset): Pair<Int, Int> {
        val s = fit() * zoom
        val ox = (box.width - client.width * s) / 2f + pan.x; val oy = (box.height - client.height * s) / 2f + pan.y
        return ((p.x - ox) / s).toInt().coerceIn(0, (client.width - 1).coerceAtLeast(0)) to ((p.y - oy) / s).toInt().coerceIn(0, (client.height - 1).coerceAtLeast(0))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Linux desktop" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val t0 = down.uptimeMillis
                        var maxPointers = 1; var moved = false; var dragging = false
                        var prevDist = 0f; var prevCentroid = down.position; var scrollAcc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pts = ev.changes.filter { it.pressed }
                            if (pts.isEmpty()) {
                                val up = ev.changes.firstOrNull()
                                val quick = (up?.uptimeMillis ?: t0) - t0 < 350
                                if (dragging) { val (x, y) = toDesk(up?.position ?: down.position); client.pointer(0, x, y) }
                                else if (!moved && quick) {
                                    val (x, y) = toDesk(down.position)
                                    client.click(x, y, if (maxPointers >= 2) 3 else 1)
                                }
                                break
                            }
                            maxPointers = maxOf(maxPointers, pts.size)
                            if (pts.size >= 2) {
                                val a = pts[0].position; val b = pts[1].position
                                val dist = hypot(a.x - b.x, a.y - b.y); val cen = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                                if (prevDist > 0f) {
                                    val z = dist / prevDist
                                    if (abs(z - 1f) > 0.01f) { zoom = (zoom * z).coerceIn(1f, 4f); moved = true; if (zoom == 1f) pan = Offset.Zero }
                                    else {
                                        scrollAcc += cen.y - prevCentroid.y
                                        if (abs(scrollAcc) > 40f) { val (x, y) = toDesk(cen); client.scroll(x, y, up = scrollAcc > 0); scrollAcc = 0f; moved = true }
                                    }
                                }
                                prevDist = dist; prevCentroid = cen
                            } else if (maxPointers == 1) {
                                val ch = pts[0]
                                val d = ch.position - down.position
                                if (!moved && hypot(d.x, d.y) > viewConfiguration.touchSlop) moved = true
                                if (moved && ch.positionChanged()) {
                                    if (zoom > 1f) pan += ch.position - ch.previousPosition
                                    else { val (x, y) = toDesk(ch.position); client.pointer(1, x, y); dragging = true }
                                }
                            }
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }) {
                box = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                if (drawn < 0) return@Canvas   // reading it redraws after every copy
                val b = bmp.value ?: return@Canvas
                val s = fit() * zoom
                val ox = (size.width - b.width * s) / 2f + pan.x; val oy = (size.height - b.height * s) / 2f + pan.y
                drawContext.canvas.save()
                drawContext.canvas.translate(ox, oy); drawContext.canvas.scale(s, s)
                drawImage(b.asImageBitmap())
                drawContext.canvas.restore()
            }
            if (client.width == 0 || error.isNotBlank()) Text(if (error.isNotBlank()) error else "Connecting\u2026",
                Modifier.align(Alignment.Center).padding(24.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Color.White))
            // Controls: keyboard, rotate, close, stop.
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewerChip("\u2328", "Keyboard") { keyboard = !keyboard }
                ViewerChip("\u21bb", "Turn sideways") { landscape = !landscape }
                ViewerChip("\u2715", "Close the viewer (keeps the desktop running)") { onClose() }
                ViewerChip("\u25a0", "Stop the desktop and free memory") { scope.launch { LinuxDesktop.stop() }; onClose() }
            }
            if (keyboard) KeyInput(client, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            // v0.25.5: what Blaze is doing, when it's working in the desktop you're watching.
            val live by com.blaze.assistant.desktop.DesktopLive.state.collectAsState()
            var tick by remember { mutableIntStateOf(0) }
            LaunchedEffect(live.active) { while (live.active) { delay(400); tick++ } }
            val t = remember(tick) { System.currentTimeMillis() }
            if (live.show(t)) StatusPill(live.pill(t), working = live.acting, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(14.dp))
        }
    }
}

@Composable
private fun ViewerChip(mark: String, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xCC1B1D24)).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) {
        Text(mark, style = TextStyle(fontFamily = Type.Sans, fontSize = 17.sp, color = Color.White))
    }
}

/** A hidden text field that turns typing into key presses (Backspace when text shrinks, Enter on the action key). */
@Composable
private fun KeyInput(client: Rfb.Client, modifier: Modifier) {
    var text by remember { mutableStateOf(" ") }
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { fr.requestFocus(); kb?.show() }
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to Rfb.XK_ESCAPE, "Tab" to Rfb.XK_TAB, "\u2190" to Rfb.XK_LEFT, "\u2191" to Rfb.XK_UP, "\u2193" to Rfb.XK_DOWN, "\u2192" to Rfb.XK_RIGHT, "Del" to Rfb.XK_DELETE).forEach { (l, k) ->
                Box(Modifier.clip(BlazeShapes.pill).background(Color(0xCC1B1D24)).clickable { client.key(k) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(l, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White))
                }
            }
        }
        BasicTextField(text, { n ->
            when {
                n.length < text.length -> repeat(text.length - n.length) { client.key(Rfb.XK_BACKSPACE) }
                n.length > text.length -> client.type(n.substring(text.length))
            }
            text = if (n.isEmpty() || n.length > 200) " " else n
        }, Modifier.size(1.dp).focusRequester(fr),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false),
            keyboardActions = KeyboardActions(onSend = { client.key(Rfb.XK_RETURN) }))
    }
}
