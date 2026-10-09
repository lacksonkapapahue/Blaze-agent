package com.blaze.assistant.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.Label
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.hairline
import com.blaze.assistant.ui.hairlineBottom
import com.blaze.assistant.ui.hairlineTop

/** The Browser screen: shows BrowserEngine's current tab with an address bar, tabs, bookmarks, history and downloads. */
class BrowserActivity : ComponentActivity() {
    private var pending: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending?.onReceiveValue(if (r.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null)
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT))
        BrowserEngine.init(this)
        BrowserEngine.filePicker = { cb, p ->
            pending?.onReceiveValue(null); pending = cb
            try { picker.launch(p.createIntent()); true } catch (_: Exception) { pending = null; cb.onReceiveValue(null); false }
        }
        handle(intent)
        if (BrowserEngine.tabs.isEmpty()) BrowserEngine.newTab(this)
        setContent { BlazeTheme { BrowserScreen(onClose = { finish() }) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val u = i?.dataString ?: i?.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value } ?: return
        BrowserEngine.newTab(this, u)
    }

    override fun onResume() { super.onResume(); BrowserEngine.attach(this); BrowserEngine.tab()?.web?.onResume() }
    override fun onPause() { BrowserEngine.attach(null); super.onPause() }
    override fun onDestroy() {
        BrowserEngine.attach(null); BrowserEngine.filePicker = null
        BrowserEngine.tabs.forEach { (it.web.parent as? ViewGroup)?.removeView(it.web) }
        super.onDestroy()
    }
}

private enum class Panel { NONE, TABS, MENU, BOOKMARKS, HISTORY }

@Composable
private fun BrowserScreen(onClose: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val tab = BrowserEngine.tab()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var editing by remember { mutableStateOf(false) }
    var bar by remember(tab?.id, tab?.url, editing) { mutableStateOf(if (editing) tab?.url.orEmpty().let { if (it == "about:blank") "" else it } else display(tab?.url.orEmpty())) }
    var tick by remember { mutableIntStateOf(0) }   // re-read bookmarks after a change

    BackHandler {
        when {
            BrowserEngine.fullView != null -> BrowserEngine.exitFull()
            panel != Panel.NONE -> panel = Panel.NONE
            tab?.web?.canGoBack() == true -> tab.web.goBack()
            else -> onClose()
        }
    }

    BrowserEngine.fullView?.let { v ->
        AndroidView(factory = { FrameLayout(it) }, update = { f -> if (v.parent !== f) { (v.parent as? ViewGroup)?.removeView(v); f.removeAllViews(); f.addView(v) } },
            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        return
    }

    Column(Modifier.fillMaxSize().background(c.paper).systemBarsPadding().imePadding()) {
        // Address bar
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("×") { onClose() }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f).height(40.dp).hairline(c.hair).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (bar.isEmpty()) Text("Search or type a web address", c.weak, 14)
                BasicTextField(bar, { bar = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { tab?.let { BrowserEngine.load(it, bar) }; focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused })
            }
            Spacer(Modifier.width(6.dp))
            IconBtn(if ((tab?.progress ?: 100) < 100) "■" else "↻") { tab?.web?.let { if (tab.progress < 100) it.stopLoading() else it.reload() } }
        }
        // Progress
        val p = tab?.progress ?: 100
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (p < 100) Box(Modifier.fillMaxWidth(p / 100f).fillMaxHeight().background(c.accent))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab != null) {
                val blank = tab.url.isBlank() || tab.url == "about:blank"
                key(tab.id) {
                    AndroidView(factory = { FrameLayout(it) }, update = { f ->
                        val w = tab.web
                        if (w.parent !== f) { (w.parent as? ViewGroup)?.removeView(w); f.removeAllViews(); f.addView(w, FrameLayout.LayoutParams(-1, -1)) }
                    }, modifier = Modifier.fillMaxSize())
                }
                if (blank) StartPage(tick) { u -> BrowserEngine.load(tab, u); panel = Panel.NONE }
            }
            when (panel) {
                Panel.TABS -> Sheet {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("${BrowserEngine.tabs.size} tabs", modifier = Modifier.weight(1f))
                        Label("+ New tab", color = c.accent, modifier = Modifier.clickable { BrowserEngine.newTab(ctx); panel = Panel.NONE })
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(BrowserEngine.tabs.toList(), key = { it.id }) { t ->
                            val i = BrowserEngine.tabs.indexOf(t)
                            Row(Modifier.fillMaxWidth().background(if (i == BrowserEngine.current) c.faint else c.paper).hairlineTop(c.hair)
                                .clickable { BrowserEngine.current = i; panel = Panel.NONE }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title.ifBlank { "New tab" }, c.ink, 14, maxLines = 1)
                                    Text(display(t.url).ifBlank { "Start page" }, c.sub, 12, maxLines = 1)
                                }
                                Text("×", c.sub, 20, modifier = Modifier.clickable { BrowserEngine.close(i); if (BrowserEngine.tabs.isEmpty()) BrowserEngine.newTab(ctx) }.padding(8.dp))
                            }
                        }
                    }
                }
                Panel.MENU -> Sheet {
                    val url = tab?.url.orEmpty()
                    val marked = remember(url, tick) { BrowserData.isBookmarked(ctx, url) }
                    MenuRow("New tab") { BrowserEngine.newTab(ctx); panel = Panel.NONE }
                    if (url.startsWith("http")) MenuRow(if (marked) "Remove bookmark" else "Bookmark this page") { BrowserData.toggleBookmark(ctx, tab?.title.orEmpty(), url); tick++; panel = Panel.NONE }
                    MenuRow("Bookmarks") { panel = Panel.BOOKMARKS }
                    MenuRow("History") { panel = Panel.HISTORY }
                    MenuRow(if (BrowserEngine.desktop) "Mobile site" else "Desktop site") { BrowserEngine.useDesktop(!BrowserEngine.desktop); panel = Panel.NONE }
                    if (url.startsWith("http")) {
                        MenuRow("Ask Blaze about this page") {
                            ctx.startActivity(Intent(ctx, com.blaze.assistant.MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Summarise the page open in your browser (use browser_read): $url").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            panel = Panel.NONE
                        }
                        MenuRow("Share link") {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); panel = Panel.NONE }
                        MenuRow("Open in another app") {
                            try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            panel = Panel.NONE }
                    }
                    MenuRow("Downloads") {
                        try { ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                        panel = Panel.NONE }
                    MenuRow("Close all tabs") { BrowserEngine.closeAll(); BrowserEngine.newTab(ctx); panel = Panel.NONE }
                }
                Panel.BOOKMARKS -> Sheet { EntryList("Bookmarks", BrowserData.bookmarks(ctx), "No bookmarks yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE } }
                Panel.HISTORY -> Sheet {
                    EntryList("History", BrowserData.history(ctx), "No history yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE }
                    MenuRow("Clear history") { BrowserData.clearHistory(ctx); panel = Panel.NONE }
                }
                Panel.NONE -> {}
            }
        }

        // Bottom bar
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarBtn("‹", enabled = tab?.canBack == true, modifier = Modifier.weight(1f)) { tab?.web?.goBack() }
            BarBtn("›", enabled = tab?.canForward == true, modifier = Modifier.weight(1f)) { tab?.web?.goForward() }
            BarBtn("⌂", modifier = Modifier.weight(1f)) { tab?.let { BrowserEngine.load(it, "about:blank") }; panel = Panel.NONE }
            Box(Modifier.weight(1f).height(44.dp).clickable { panel = if (panel == Panel.TABS) Panel.NONE else Panel.TABS }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).hairline(c.ink), contentAlignment = Alignment.Center) { Text("${BrowserEngine.tabs.size}", c.ink, 12) }
            }
            BarBtn("⋯", modifier = Modifier.weight(1f)) { panel = if (panel == Panel.MENU) Panel.NONE else Panel.MENU }
        }
    }
}

private fun display(u: String) = if (u == "about:blank") "" else u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

@Composable
private fun StartPage(tick: Int, open: (String) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val marks = remember(tick) { BrowserData.bookmarks(ctx) }
    val recent = remember(tick) { BrowserData.history(ctx).take(6) }
    val quick = listOf("Google" to "google.com", "YouTube" to "youtube.com", "Wikipedia" to "wikipedia.org", "Gmail" to "mail.google.com")
    LazyColumn(Modifier.fillMaxSize().background(c.paper).padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(36.dp))
            Text("Blaze browser", c.ink, 26, weight = FontWeight.Medium)
            Text("Blaze can browse here for you in the background: open pages, fill forms and read them, without touching your screen.", c.sub, 13)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) { quick.forEach { (n, u) ->
                Box(Modifier.weight(1f).height(44.dp).hairline(c.hair).clickable { open(u) }, contentAlignment = Alignment.Center) { Label(n, color = c.ink, size = 10.sp) } } }
        }
        if (marks.isNotEmpty()) { item { Spacer(Modifier.height(24.dp)); Label("Bookmarks") }
            items(marks.take(12)) { e -> EntryRow(e) { open(e.url) } } }
        if (recent.isNotEmpty()) { item { Spacer(Modifier.height(24.dp)); Label("Recent") }
            items(recent) { e -> EntryRow(e) { open(e.url) } } }
    }
}

@Composable private fun EntryList(title: String, l: List<BrowserData.Entry>, empty: String, open: (String) -> Unit) {
    Label(title, modifier = Modifier.padding(16.dp))
    if (l.isEmpty()) Text(empty, LocalBlaze.current.sub, 13, modifier = Modifier.padding(start = 16.dp, bottom = 16.dp))
    LazyColumn(Modifier.heightIn(max = 420.dp)) { items(l.take(150)) { e -> EntryRow(e) { open(e.url) } } }
}

@Composable private fun EntryRow(e: BrowserData.Entry, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().hairlineTop(c.hair).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(e.title.ifBlank { e.url }, c.ink, 14, maxLines = 1)
        Text(display(e.url), c.sub, 12, maxLines = 1)
    }
}

@Composable private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.25f)), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.hair), content = content)
    }
}

@Composable private fun MenuRow(text: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Text(text, c.ink, 15, modifier = Modifier.fillMaxWidth().hairlineTop(c.hair).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp))
}

@Composable private fun IconBtn(s: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(40.dp).hairline(c.hair).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(s, c.ink, 18) }
}

@Composable private fun BarBtn(s: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(modifier.height(44.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) { Text(s, if (enabled) c.ink else c.weak, 22) }
}

@Composable private fun Text(s: String, color: androidx.compose.ui.graphics.Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, weight: FontWeight = FontWeight.Normal) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = if (size >= 20) Type.Display else Type.Sans, fontWeight = weight, fontSize = size.sp, color = color))
