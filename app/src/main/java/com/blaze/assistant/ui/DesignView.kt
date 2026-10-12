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
 * Design view: a live preview of what Blaze builds (HTML files, local servers, URLs), phone or
 * desktop width, with a one-line box to ask for changes without leaving. Exit is in the top bar.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DesignView(target: String, busy: Boolean, status: String, onAsk: (String) -> Unit, onOpenInBrowser: (String) -> Unit, home: File?, preview: Boolean = true) {
    val c = LocalBlaze.current
    val url = remember(target) { designUrl(target, home) }
    var wide by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf("") }
    // When Blaze finishes a turn, show its latest version.
    var wasBusy by remember { mutableStateOf(busy) }
    androidx.compose.runtime.LaunchedEffect(busy) { if (wasBusy && !busy) reload++; wasBusy = busy }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "Phone", true to "Desktop").forEach { (w, label) ->
                val on = wide == w
                Box(Modifier.background(if (on) c.ink else c.paper).hairline(if (on) c.ink else c.hair).clickable { wide = w }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Label(label, color = if (on) c.paper else c.ink, size = 9.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(url.ifBlank { "Nothing to preview yet" }, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
            if (url.isNotBlank()) {
                Label("Reload", color = c.accent, size = 9.sp, modifier = Modifier.clickable { reload++ }.padding(6.dp))
                Label("Open", color = c.accent, size = 9.sp, modifier = Modifier.clickable { onOpenInBrowser(url) }.padding(6.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(c.faint)) {
            if (url.isBlank()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("Design")
                    Spacer(Modifier.padding(4.dp))
                    Text("Ask Blaze for a page, poster or app screen and it opens here.",
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
                }
            } else if (preview) {
                androidx.compose.runtime.key(url, wide, reload) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.useWideViewPort = wide
                            settings.loadWithOverviewMode = wide
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, u: String?) {
                                    if (wide) view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');" +
                                        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=1280';})()", null)
                                }
                            }
                            loadUrl(url)
                        }
                    })
                }
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
