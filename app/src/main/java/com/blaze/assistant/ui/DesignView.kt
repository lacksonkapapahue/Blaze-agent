package com.blaze.assistant.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.io.File

/** Turns what Blaze passed (URL, ~/path, relative path, absolute path) into something WebView can load. */
fun designUrl(target: String, home: File?): String {
    val t = target.trim()
    if (t.isEmpty()) return ""
    if (Regex("^(https?|file|about|data):", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
    if (Regex("^(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?$").matches(t)) return "http://$t"
    val f = when {
        t.startsWith("~/") && home != null -> File(home, t.removePrefix("~/"))
        t.startsWith("/") -> File(t)
        home != null -> File(home, t)
        else -> File(t)
    }
    return "file://" + (if (f.isDirectory) File(f, "index.html") else f).absolutePath
}

/**
 * v0.23.2: three.js works offline. 3D designs import three.js from a CDN (importmap); the Design view
 * serves those requests from copies bundled in the APK (assets/design/three, three.js r170, MIT), so a
 * scene opens with no network and never waits on the CDN. Returns the asset name for [url], or null.
 */
fun bundledThree(url: String): String? {
    val u = url.substringBefore('?').substringBefore('#')
    if (!Regex("^https?://(cdn\\.jsdelivr\\.net|unpkg\\.com|cdnjs\\.cloudflare\\.com|esm\\.sh|ga\\.jspm\\.io)/", RegexOption.IGNORE_CASE).containsMatchIn(u)) return null
    if (!u.contains("three", ignoreCase = true)) return null
    return when {
        Regex("/build/three\\.module(\\.min)?\\.js$").containsMatchIn(u) || Regex("/three\\.js/r?[\\d.]+/three\\.module(\\.min)?\\.js$").containsMatchIn(u) -> "three.module.min.js"
        u.endsWith("/examples/jsm/controls/OrbitControls.js") || u.endsWith("/addons/controls/OrbitControls.js") -> "OrbitControls.js"
        u.endsWith("/examples/jsm/exporters/GLTFExporter.js") || u.endsWith("/addons/exporters/GLTFExporter.js") -> "GLTFExporter.js"
        // r162's GLTFExporter imports ../utils/TextureUtils.js (only bundled with the r162 build).
        u.endsWith("/examples/jsm/utils/TextureUtils.js") || u.endsWith("/addons/utils/TextureUtils.js") -> "TextureUtils.js"
        else -> null
    }
}

/** The blaze-parts spec of a 3D design file, or null (not a parts-based 3D design, or unreadable). */
fun designSpec(target: String, home: File?): org.json.JSONObject? = try {
    val u = designUrl(target, home)
    if (!u.startsWith("file://")) null else File(u.removePrefix("file://")).takeIf { it.isFile && it.length() < 2_000_000 }
        ?.let { com.blaze.assistant.agent.Designs.specFrom(it.readText()) }
} catch (_: Exception) { null }

/**
 * v0.23.3: the JS bridge a Design page calls (window.BlazeDesign), injected by [DesignServe.HOOK_JS].
 * Calls arrive on a WebView binder thread; they're posted to the main thread.
 */
class DesignBridge(private val onGl: (Int, Boolean, String) -> Unit, private val onError: (String) -> Unit,
                   private val onReady: (String) -> Unit, private val onFail: (String) -> Unit) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun gl(level: Int, gl1: Boolean, engine: String) { main.post { onGl(level, gl1, engine) } }
    @android.webkit.JavascriptInterface fun error(msg: String) { main.post { onError(msg) } }
    @android.webkit.JavascriptInterface fun ready(engine: String) { main.post { onReady(engine) } }
    @android.webkit.JavascriptInterface fun fail(msg: String) { main.post { onFail(msg) } }
}

/** What the current attempt to draw a design has reported. */
private class RenderState {
    var ready by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var forceGl1 by mutableStateOf(false)
    var glLevel = -1
    var gl1 = false
    /** Read from the WebView's request thread (CDN fallback picks the same build as the page). */
    @Volatile var engine: String? = null
    val errors = ArrayList<String>()
    var grace: kotlinx.coroutines.Job? = null
    fun begin() { ready = false; failure = null; glLevel = -1; gl1 = false; engine = null; errors.clear(); grace?.cancel(); grace = null }
}

/**
 * Design view: a live preview of what Blaze builds (HTML files, local servers, URLs), phone or
 * desktop width, with a one-line box to ask for changes without leaving. Exit is in the top bar.
 *
 * v0.23.3: designs in Blaze's home load from a virtual https origin ([DesignServe]); 3D pages report
 * ready / errors through [DesignBridge]; when three.js can't start, the view retries with the WebGL1 build
 * and then draws the parts natively ([PartsSketchView]) with the real error and Retry, so a 3D design
 * always shows something. Errors go to ~/designs/.last_error.txt for Blaze's design tool.
 * [initialFailure] shows the fallback straight away (screenshots).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DesignView(target: String, busy: Boolean, status: String, onAsk: (String) -> Unit, onOpenInBrowser: (String) -> Unit, home: File?,
               preview: Boolean = true, initialFailure: String? = null) {
    val c = LocalBlaze.current
    val fileUrl = remember(target, home) { designUrl(target, home) }
    val url = remember(fileUrl, home) { DesignServe.virtualUrl(fileUrl, home) }
    var wide by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf("") }
    val spec3d = remember(target, reload) { designSpec(target, home) }
    val is3d = spec3d != null
    val rs = remember(url) { RenderState().also { it.failure = initialFailure } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val errorFile = remember(home) { home?.let { File(File(it, "designs"), DesignServe.ERROR_FILE) } }

    fun writeErrors() {
        val f = errorFile ?: return
        val text = DesignServe.errorReport(fileUrl.removePrefix("file://"), rs.engine, rs.glLevel, rs.errors.toList(), System.currentTimeMillis())
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { try { f.parentFile?.mkdirs(); f.writeText(text) } catch (_: Exception) { } }
    }
    fun restart(forceGl1: Boolean) { rs.forceGl1 = forceGl1; rs.begin(); reload++ }
    fun fail(msg: String) {
        if (rs.failure != null) return
        rs.grace?.cancel()
        if (rs.errors.none { it == msg }) rs.errors += msg
        writeErrors()
        val engine = if (rs.forceGl1) DesignServe.R162 else rs.engine
        when (DesignServe.onFail(engine, rs.gl1, is3d)) {
            DesignServe.Next.RETRY_WEBGL1 -> restart(true)
            else -> { rs.ready = false; rs.failure = msg }
        }
    }
    fun noteError(msg: String, fatal: Boolean) {
        if (rs.errors.size >= 20) rs.errors.removeAt(0)
        rs.errors += msg
        writeErrors()
        if (!is3d || rs.failure != null) return
        if (fatal) { fail(msg); return }
        if (!rs.ready && rs.grace == null) rs.grace = scope.launch {
            kotlinx.coroutines.delay(DesignServe.ERROR_GRACE_MS)
            if (!rs.ready && rs.failure == null) fail(rs.errors.firstOrNull() ?: msg)
        }
    }

    // When Blaze finishes a turn, show its latest version.
    var wasBusy by remember { mutableStateOf(busy) }
    androidx.compose.runtime.LaunchedEffect(busy) { if (wasBusy && !busy) restart(rs.forceGl1); wasBusy = busy }
    // A 3D page has READY_TIMEOUT_MS to say it's drawing (the A12 parses three.js slowly).
    androidx.compose.runtime.LaunchedEffect(url, wide, reload, preview) {
        if (preview && is3d && rs.failure == null) {
            kotlinx.coroutines.delay(DesignServe.READY_TIMEOUT_MS)
            if (!rs.ready && rs.failure == null) fail("The 3D engine didn't start within ${DesignServe.READY_TIMEOUT_MS / 1000} s (" + (rs.engine?.ifBlank { null } ?: "no engine") + ").")
        }
    }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "Phone", true to "Desktop").forEach { (w, label) ->
                val on = wide == w
                Box(Modifier.background(if (on) c.ink else c.paper).hairline(if (on) c.ink else c.hair).clickable { wide = w }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Label(label, color = if (on) c.paper else c.ink, size = 9.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(target.trim().ifBlank { "Nothing to preview yet" }.let { if (it.length > 34) "…" + it.takeLast(33) else it }, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
            if (url.isNotBlank()) {
                Label("Reload", color = c.accent, size = 9.sp, modifier = Modifier.clickable { restart(rs.forceGl1) }.padding(6.dp))
                // "Open" hands the real file (or URL) to the browser, never the in-app virtual origin.
                Label("Open", color = c.accent, size = 9.sp, modifier = Modifier.clickable { onOpenInBrowser(fileUrl) }.padding(6.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(c.faint)) {
            val failure = rs.failure
            if (url.isBlank()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("Design")
                    Spacer(Modifier.padding(4.dp))
                    Text("Ask Blaze for anything visual: a 3D model, a site, an app screen, a diagram, a chart, a poster. It opens here.",
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
                }
            } else if (spec3d != null && (!preview || failure != null)) {
                // No WebGL (screenshots), or three.js couldn't start on this phone: draw the same parts natively.
                // The banner sits under the drawing (not over it), so the whole model stays visible.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PartsSketchView(spec3d)
                        Label("3D \u00b7 " + spec3d.optString("name") + if (failure != null) " \u00b7 simple view" else "", color = c.sub, size = 9.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                    }
                    if (failure != null) FailBanner(failure, sketch = true, onRetry = { restart(false) })
                }
            } else if (preview) {
                androidx.compose.runtime.key(url, wide, reload) {
                    val loadUrl = DesignServe.attemptUrl(url, rs.forceGl1)
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.useWideViewPort = wide
                            settings.loadWithOverviewMode = wide
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            addJavascriptInterface(DesignBridge(
                                onGl = { level, gl1, engine -> rs.glLevel = level; rs.gl1 = gl1; rs.engine = engine },
                                onError = { noteError(it, fatal = false) },
                                onReady = { engine -> rs.grace?.cancel(); rs.grace = null; rs.ready = true; rs.engine = engine
                                    try { errorFile?.delete() } catch (_: Exception) { } },
                                onFail = { fail(it) }), "BlazeDesign")
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                        val t = m.message() ?: ""
                                        // "Uncaught …" also reaches window.onerror, which the hook reports with more context.
                                        if (!t.startsWith("Uncaught")) noteError("console: $t (" + (m.sourceId() ?: "").substringAfterLast('/') + ":" + m.lineNumber() + ")",
                                            fatal = DesignServe.fatalConsole(t))
                                        else if (DesignServe.fatalConsole(t)) noteError(t, fatal = true)
                                    }
                                    return false
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                                    serveDesign(ctx, req.url.toString(), home, rs.engine)
                                override fun onPageFinished(view: WebView, u: String?) {
                                    if (wide) view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');" +
                                        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=1280';})()", null)
                                }
                            }
                            loadUrl(loadUrl)
                        }
                    })
                }
                if (failure != null) FailBanner(failure, sketch = false, onRetry = { restart(false) }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).hairline(c.hair).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (ask.isEmpty()) Text(if (busy) status.ifBlank { "Working…" } else "Ask for a change to this design", maxLines = 1,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = if (busy) c.accent else c.weak))
                BasicTextField(ask, { ask = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (ask.isNotBlank() && !busy) { onAsk(ask); ask = "" } }),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.background(if (ask.isNotBlank() && !busy) c.ink else c.faint).clickable(enabled = ask.isNotBlank() && !busy) { onAsk(ask); ask = "" }
                .padding(horizontal = 14.dp, vertical = 12.dp)) { Label("Send", color = if (ask.isNotBlank() && !busy) c.paper else c.weak, size = 10.sp) }
        }
    }
}

/** The "3D engine couldn't start" banner: the real error, what's shown instead, and Retry. */
@Composable
private fun FailBanner(error: String, sketch: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(12.dp).background(androidx.compose.ui.graphics.Color(0xFFFFF3E0))
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("The 3D engine couldn't start: " + error.take(220), maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFF6D3D00)))
            if (sketch) Text("Showing a simple drawing instead: drag to turn, pinch to zoom.",
                style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = androidx.compose.ui.graphics.Color(0xFF8A5A1C)))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.background(androidx.compose.ui.graphics.Color(0xFF6D3D00)).clickable { onRetry() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Label("Retry", color = androidx.compose.ui.graphics.Color.White, size = 10.sp)
        }
    }
}

/**
 * Answers a Design view request: the virtual origin (designs, Blaze's home, bundled three.js) and CDN three.js
 * URLs from the APK (the build matching [engine]). Null = let the network have it.
 */
fun serveDesign(ctx: android.content.Context, url: String, home: File?, engine: String?): android.webkit.WebResourceResponse? {
    fun notFound() = android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
    fun ok(mime: String, data: java.io.InputStream, cache: String) = android.webkit.WebResourceResponse(mime,
        if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) "utf-8" else null, 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to cache), data)
    return try {
        when (val t = DesignServe.resolve(url, home)) {
            is DesignServe.Target.Asset -> try { ok(t.mime, ctx.assets.open(t.path), "max-age=86400") } catch (_: Exception) { notFound() }
            is DesignServe.Target.Disk -> when {
                !t.file.isFile -> notFound()
                t.mime == "text/html" -> {
                    val html = t.file.readText()
                    val is3d = com.blaze.assistant.agent.Designs.specFrom(html) != null
                    ok("text/html", java.io.ByteArrayInputStream(DesignServe.prepareHtml(html, is3d).toByteArray(Charsets.UTF_8)), "no-store")
                }
                else -> ok(t.mime, java.io.FileInputStream(t.file), "no-store")
            }
            null -> {
                if (url.startsWith(DesignServe.ORIGIN, ignoreCase = true)) return notFound()
                val name = bundledThree(url) ?: return null
                val asset: java.io.InputStream = (try { ctx.assets.open(DesignServe.assetDir(engine ?: DesignServe.R170) + "/" + name) }
                    catch (_: Exception) { try { ctx.assets.open("design/three/$name") } catch (_: Exception) { null } }) ?: return null
                ok("text/javascript", asset, "max-age=86400")
            }
        }
    } catch (_: Exception) { null }
}
