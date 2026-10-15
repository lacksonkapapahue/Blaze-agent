package com.blaze.assistant.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.foundation.combinedClickable
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
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.BrandProgress
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
    /** VPN configs: .conf files or a provider's .zip, several at once. */
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val added = mutableListOf<String>(); val bad = mutableListOf<String>()
        uris.forEach { u ->
            try {
                val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "config.conf"
                val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.size > 5_000_000) { bad += "$name is too big to be a WireGuard config."; return@forEach }
                val (a, b) = BrowserVpn.import(name, bytes)
                added += a.map { "${it.flag} ${it.label}" }; bad += b
            } catch (e: Exception) { bad += e.message ?: "Couldn't read a file." }
        }
        val msg = (if (added.isNotEmpty()) "Added ${added.size}: ${added.take(6).joinToString()}${if (added.size > 6) "…" else ""}." else "") +
            (if (bad.isNotEmpty()) " Skipped ${bad.size}: ${bad.first()}" else "")
        Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show()
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
        setContent { BlazeTheme { BrowserScreen(onClose = { finish() }, onImport = { try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { } },
            onPopOut = { w, h -> popOut(w, h) }) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    /** v0.24.6: the page pointer's Pop out: the video under the pointer plays in a small window over other apps. */
    fun popOut(w: Float, h: Float): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return try {
            val r = if (w > 0f && h > 0f) (w / h).coerceIn(0.42f, 2.38f) else 16f / 9f
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build())
        } catch (_: Exception) { false }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        BrowserUi.pip.value = isInPictureInPictureMode
    }

    private fun handle(i: Intent?) {
        val u = i?.dataString ?: i?.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value } ?: return
        BrowserEngine.newTab(this, u)
    }

    override fun onResume() { super.onResume(); BrowserEngine.attach(this); BrowserEngine.tab()?.web?.onResume() }
    override fun onPause() { BrowserEngine.attach(null); super.onPause() }
    override fun onDestroy() {
        BrowserEngine.attach(null); BrowserEngine.filePicker = null
        // Back to the background browser: the tabs keep running offscreen at screen size.
        BrowserEngine.tabs.forEach { (it.web.parent as? ViewGroup)?.removeView(it.web); BrowserEngine.layoutOffscreen(it.web) }
        super.onDestroy()
    }
}

/** Browser screen state shared with the activity. */
object BrowserUi {
    val pip = androidx.compose.runtime.mutableStateOf(false)
    /** The summoned page pointer (v0.24.6). */
    val pointer = androidx.compose.runtime.mutableStateOf(false)
}

private enum class Panel { NONE, TABS, MENU, BOOKMARKS, HISTORY, PRIVACY }

/**
 * The Browser (v0.24.6 look): rounded dark Blaze theme with a pill address bar, rounded tab chips, round icon
 * buttons and a brand-coloured progress shimmer. The mascot button summons the page pointer (long-press too);
 * the sparkle button opens "Ask Blaze about this page".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BrowserScreen(onClose: () -> Unit, onImport: () -> Unit, onPopOut: (Float, Float) -> Boolean = { _, _ -> false },
                           previewPointer: PagePointer.Hit? = null, previewMenu: Boolean = false, previewAsk: List<Pair<Boolean, String>>? = null,
                           previewPage: (@Composable () -> Unit)? = null, previewTip: androidx.compose.ui.geometry.Offset? = null) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val tab = BrowserEngine.tab()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var editing by remember { mutableStateOf(false) }
    var bar by remember(tab?.id, tab?.url, editing) { mutableStateOf(if (editing) tab?.url.orEmpty().let { if (it == "about:blank") "" else it } else display(tab?.url.orEmpty())) }
    var tick by remember { mutableIntStateOf(0) }   // re-read bookmarks after a change
    var pointer by BrowserUi.pointer
    if (previewPointer != null) pointer = true
    var askOpen by remember { mutableStateOf(previewAsk != null) }
    var askFirst by remember { mutableStateOf<String?>(null) }
    val pip by BrowserUi.pip
    val priv = tab?.isPrivate == true
    val bg = if (priv) Glass.privateBg else Glass.bg

    BackHandler {
        when {
            BrowserEngine.fullView != null -> BrowserEngine.exitFull()
            askOpen -> askOpen = false
            pointer -> pointer = false
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

    if (panel == Panel.TABS) {
        Box(Modifier.fillMaxSize().background(if (priv) androidx.compose.ui.graphics.Color(0xFF202124) else LocalBlaze.current.paper).systemBarsPadding()) {
            TabGrid(startPrivate = priv, onDone = { panel = Panel.NONE })
        }
        return
    }

    // Picture-in-picture: only the page (the video was scrolled to the middle and started).
    if (pip && tab != null) {
        key(tab.id) { WebHost(tab) }
        return
    }

    val host = remember(tab?.id) {
        PointerHost(
            openUrl = { u, mode ->
                when (mode) {
                    OpenMode.SAME -> BrowserEngine.tab()?.let { BrowserEngine.load(it, u) }
                    OpenMode.NEW -> BrowserEngine.newTab(ctx, u, isPrivate = priv)
                    OpenMode.BACKGROUND -> { val keep = BrowserEngine.current; BrowserEngine.newTab(ctx, u, isPrivate = priv); BrowserEngine.current = keep }
                }
            },
            ask = { q -> askFirst = q; askOpen = true },
            pip = { h, x, y ->
                BrowserEngine.tab()?.web?.evaluateJavascript(PagePointer.focusVideoJs(x, y), null)
                pointer = false
                if (!onPopOut(h.w, h.h)) android.widget.Toast.makeText(ctx, "This phone doesn't allow picture-in-picture for Blaze.", android.widget.Toast.LENGTH_SHORT).show()
            },
            isPrivate = priv)
    }

    Column(Modifier.fillMaxSize().background(bg).systemBarsPadding().imePadding()) {
        // Address row: close, privacy, the address pill, reload, ask.
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.Close, "Close browser", size = 38.dp, onClick = onClose)
            Spacer(Modifier.width(6.dp))
            val vpn = BrowserVpn.status
            Box(Modifier.size(38.dp).clip(CircleShape).background(Glass.raised).clickable { panel = if (panel == Panel.PRIVACY) Panel.NONE else Panel.PRIVACY }
                .semantics { contentDescription = "VPN and privacy" }, contentAlignment = Alignment.Center) {
                when (vpn) {
                    BrowserVpn.Status.ON -> Text(Countries.flag(BrowserVpn.exitCountry ?: BrowserVpn.current()?.country.orEmpty()), Glass.text, 16)
                    BrowserVpn.Status.CONNECTING -> Icon(Icons.Rounded.VpnLock, null, tint = Glass.orange, modifier = Modifier.size(19.dp))
                    BrowserVpn.Status.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = Glass.orange, modifier = Modifier.size(19.dp))
                    BrowserVpn.Status.OFF -> Icon(Icons.Rounded.Shield, null, tint = Glass.sub, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f).height(42.dp).clip(Glass.pill).background(if (priv) Color(0xFF2A2433) else Glass.raised)
                .border(1.dp, if (editing) Glass.blue.copy(alpha = 0.7f) else Glass.border, Glass.pill)
                .padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // v0.28.2: a plain-http page says "Not secure" (secure forms and saved logins won't fill it unless it's on the LAN).
                val insecure = com.blaze.assistant.connect.UrlSafety.notSecure(tab?.url.orEmpty())
                Icon(when { priv -> Icons.Rounded.VisibilityOff; insecure -> Icons.Rounded.ErrorOutline; tab?.url.orEmpty().startsWith("https") -> Icons.Rounded.Lock; else -> Icons.Rounded.Search },
                    if (insecure) "Not secure" else null, tint = if (insecure) Glass.orange else Glass.weak, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                if (insecure && !editing) { Text("Not secure", Glass.orange, 12, maxLines = 1); Spacer(Modifier.width(6.dp)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (bar.isEmpty()) Text(if (priv) "Private: search or type a web address" else "Search or type a web address", Glass.weak, 14, maxLines = 1)
                    BasicTextField(bar, { bar = it }, singleLine = true, cursorBrush = SolidColor(Glass.blue),
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = Glass.text),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { tab?.let { BrowserEngine.load(it, bar) }; focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused })
                }
                val loading = (tab?.progress ?: 100) < 100
                Icon(if (loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (loading) "Stop loading" else "Reload", tint = Glass.sub,
                    modifier = Modifier.size(30.dp).clip(CircleShape).clickable { tab?.web?.let { if (loading) it.stopLoading() else it.reload() } }.padding(6.dp))
            }
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.AutoAwesome, "Ask Blaze about this page", size = 38.dp, bg = Glass.blue,
                enabled = tab?.url.orEmpty().startsWith("http") || previewPage != null) { askFirst = null; askOpen = true }
        }
        BrandProgress(tab?.progress ?: 100, Modifier.padding(horizontal = 14.dp))
        // v0.29.1: show what the page is pulling, so a slow network still looks alive.
        tab?.let { tb ->
            if (tb.progress in 0..99) {
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(tb.id, tb.loadStart) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
                val host = LoadFeed.label(tb.url).substringBefore(" ›")
                androidx.compose.material3.Text(LoadFeed.line(tb.progress, tb.pulls, tb.pulling, host, tb.loadStart, tb.lastPull, now),
                    color = Glass.sub, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp))
            }
        }
        // Rounded tab chips (only with more than one tab of this kind).
        val sameKind = BrowserEngine.tabs.filter { it.isPrivate == priv }
        if (sameKind.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sameKind.forEach { t ->
                    val on = t === tab
                    Row(Modifier.height(32.dp).widthIn(max = 170.dp).clip(Glass.pill).background(if (on) Glass.raised2 else Glass.surface)
                        .border(1.dp, if (on) Glass.blue.copy(alpha = 0.6f) else Glass.border, Glass.pill)
                        .clickable { BrowserEngine.current = BrowserEngine.tabs.indexOf(t) }.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title.ifBlank { display(t.url) }.ifBlank { "New tab" }, if (on) Glass.text else Glass.sub, 12, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Rounded.Close, "Close tab", tint = Glass.weak, modifier = Modifier.size(24.dp).clip(CircleShape)
                            .clickable { BrowserEngine.close(BrowserEngine.tabs.indexOf(t)); if (BrowserEngine.tabs.none { it.isPrivate == priv }) BrowserEngine.newTab(ctx, isPrivate = priv) }.padding(5.dp))
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)) {
            if (previewPage != null) {
                previewPage()
                if (pointer) PagePointerLayer(null, host, onClose = { pointer = false }, preview = previewPointer, previewMenu = previewMenu, startTip = previewTip)
            } else if (tab != null) {
                val blank = tab.url.isBlank() || tab.url == "about:blank"
                key(tab.id) { WebHost(tab) }
                if (blank) StartPage(tick) { u -> BrowserEngine.load(tab, u); panel = Panel.NONE }
                if (pointer) PagePointerLayer(tab.web, host, onClose = { pointer = false })
            }
            when (panel) {
                Panel.TABS, Panel.NONE -> {}
                Panel.PRIVACY -> Sheet { PrivacySheet(onImport = onImport, onPrivateTab = { BrowserEngine.newTab(ctx, isPrivate = true); panel = Panel.NONE }) }
                Panel.MENU -> Sheet {
                    val url = tab?.url.orEmpty()
                    val marked = remember(url, tick) { BrowserData.isBookmarked(ctx, url) }
                    MenuRow(Icons.Rounded.Add, "New tab") { BrowserEngine.newTab(ctx); panel = Panel.NONE }
                    MenuRow(Icons.Rounded.VisibilityOff, "New private tab") { BrowserEngine.newTab(ctx, isPrivate = true); panel = Panel.NONE }
                    MenuRow(Icons.Rounded.VpnLock, "VPN & privacy" + when (BrowserVpn.status) { BrowserVpn.Status.ON -> "  \u00b7  " + Countries.flag(BrowserVpn.exitCountry ?: BrowserVpn.current()?.country.orEmpty()); BrowserVpn.Status.FAILED -> "  \u00b7  not connected"; else -> "" }) { panel = Panel.PRIVACY }
                    if (url.startsWith("http")) MenuRow(if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (marked) "Remove bookmark" else "Bookmark this page") { BrowserData.toggleBookmark(ctx, tab?.title.orEmpty(), url); tick++; panel = Panel.NONE }
                    MenuRow(Icons.Rounded.Bookmarks, "Bookmarks") { panel = Panel.BOOKMARKS }
                    MenuRow(Icons.Rounded.History, "History") { panel = Panel.HISTORY }
                    MenuRow(if (BrowserEngine.desktop) Icons.Rounded.PhoneAndroid else Icons.Rounded.DesktopWindows, if (BrowserEngine.desktop) "Mobile site" else "Desktop site") { BrowserEngine.useDesktop(!BrowserEngine.desktop); panel = Panel.NONE }
                    if (url.startsWith("http")) {
                        MenuRow(Icons.Rounded.AutoAwesome, "Ask Blaze about this page") { askFirst = null; askOpen = true; panel = Panel.NONE }
                        MenuRow(Icons.Rounded.AdsClick, "Summon the Blaze pointer") { pointer = true; panel = Panel.NONE }
                        MenuRow(Icons.Rounded.Share, "Share link") {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); panel = Panel.NONE }
                        MenuRow(Icons.Rounded.OpenInNew, "Open in another app") {
                            try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            panel = Panel.NONE }
                    }
                    MenuRow(Icons.Rounded.Download, "Downloads") {
                        try { ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                        panel = Panel.NONE }
                    MenuRow(Icons.Rounded.ClearAll, if (priv) "Close all private tabs" else "Close all tabs") {
                        val p = priv
                        BrowserEngine.closeAll(p); if (BrowserEngine.normalTabs().isEmpty()) BrowserEngine.newTab(ctx)
                        if (!p || BrowserEngine.tab()?.isPrivate == true) BrowserEngine.current = BrowserEngine.tabs.indexOfLast { !it.isPrivate }
                        panel = Panel.NONE }
                }
                Panel.BOOKMARKS -> Sheet { EntryList("Bookmarks", BrowserData.bookmarks(ctx), "No bookmarks yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE } }
                Panel.HISTORY -> Sheet {
                    EntryList("History", BrowserData.history(ctx), "No history yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE }
                    MenuRow(Icons.Rounded.DeleteSweep, "Clear history") { BrowserData.clearHistory(ctx); panel = Panel.NONE }
                }
            }
        }

        // Bottom bar: back, forward, home, the mascot (pointer), tabs, menu.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", size = 42.dp, bg = Color.Transparent, enabled = tab?.canBack == true) { tab?.web?.goBack() }
            GlassIconButton(Icons.Rounded.ArrowForward, "Forward", size = 42.dp, bg = Color.Transparent, enabled = tab?.canForward == true) { tab?.web?.goForward() }
            GlassIconButton(Icons.Rounded.Home, "Start page", size = 42.dp, bg = Color.Transparent) { tab?.let { BrowserEngine.load(it, "about:blank") }; panel = Panel.NONE; pointer = false }
            Box(Modifier.size(50.dp).clip(CircleShape).background(if (pointer) Glass.raised2 else Glass.raised)
                .border(1.dp, if (pointer) Glass.blue else Glass.border, CircleShape)
                .combinedClickable(onClick = { pointer = !pointer; panel = Panel.NONE }, onLongClick = { pointer = true; panel = Panel.NONE },
                    onClickLabel = if (pointer) "Put the Blaze pointer away" else "Summon the Blaze pointer")
                .semantics { contentDescription = if (pointer) "Put the Blaze pointer away" else "Summon the Blaze pointer" }, contentAlignment = Alignment.Center) {
                MascotGlyph(40.dp, pulse = pointer)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).clickable { BrowserEngine.snapshot(); panel = Panel.TABS }
                .semantics { contentDescription = "Tabs" }, contentAlignment = Alignment.Center) {
                val n = BrowserEngine.tabs.count { it.isPrivate == priv }
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).border(1.6.dp, Glass.text, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(if (n > 99) ":D" else "$n", Glass.text, 11, weight = FontWeight.SemiBold) }
            }
            GlassIconButton(Icons.Rounded.MoreHoriz, "Menu", size = 42.dp, bg = Color.Transparent) { panel = if (panel == Panel.MENU) Panel.NONE else Panel.MENU }
        }
    }

    if (askOpen) PageAskSheet(title = tab?.title.orEmpty(), initial = askFirst,
        readPage = { BrowserEngine.capture(ctx) }, web = tab?.web?.takeIf { previewAsk == null },
        onDismiss = { askOpen = false; askFirst = null }, preview = previewAsk)
}

@Composable
private fun WebHost(tab: BrowserEngine.Tab) {
    AndroidView(factory = { FrameLayout(it) }, update = { f ->
        val w = tab.web
        if (w.parent !== f) { (w.parent as? ViewGroup)?.removeView(w); f.removeAllViews(); f.addView(w, FrameLayout.LayoutParams(-1, -1)) }
    }, modifier = Modifier.fillMaxSize())
}

private fun display(u: String) = if (u == "about:blank") "" else u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

@Composable
private fun StartPage(tick: Int, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val marks = remember(tick) { BrowserData.bookmarks(ctx) }
    val recent = remember(tick) { BrowserData.history(ctx).take(6) }
    val quick = listOf("Google" to "google.com", "YouTube" to "youtube.com", "Wikipedia" to "wikipedia.org", "Gmail" to "mail.google.com")
    LazyColumn(Modifier.fillMaxSize().background(Glass.bg).padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MascotGlyph(54.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Blaze browser", Glass.text, 24, weight = FontWeight.Medium)
                    Text("Tap the mascot below to point at anything on a page.", Glass.sub, 13)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { quick.forEach { (n, u) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Glass.surface).border(1.dp, Glass.border, RoundedCornerShape(18.dp))
                    .clickable { open(u) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
                        Text(n.take(1), Glass.text, 15, weight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(6.dp))
                    Text(n, Glass.sub, 11, maxLines = 1)
                } } }
        }
        if (marks.isNotEmpty()) { item { Spacer(Modifier.height(22.dp)); Head("Bookmarks") }
            items(marks.take(12)) { e -> EntryRow(e) { open(e.url) } } }
        if (recent.isNotEmpty()) { item { Spacer(Modifier.height(22.dp)); Head("Recent") }
            items(recent) { e -> EntryRow(e) { open(e.url) } } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun Head(s: String) = Text(s, Glass.sub, 12, weight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

@Composable private fun EntryList(title: String, l: List<BrowserData.Entry>, empty: String, open: (String) -> Unit) {
    Text(title, Glass.text, 16, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp))
    if (l.isEmpty()) Text(empty, Glass.sub, 13, modifier = Modifier.padding(start = 18.dp, bottom = 16.dp))
    LazyColumn(Modifier.heightIn(max = 420.dp).padding(horizontal = 8.dp)) { items(l.take(150)) { e -> EntryRow(e) { open(e.url) } } }
}

@Composable private fun EntryRow(e: BrowserData.Entry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
            Text(display(e.url).take(1).uppercase().ifBlank { "\u2022" }, Glass.text, 13, weight = FontWeight.SemiBold) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { e.url }, Glass.text, 14, maxLines = 1)
            Text(display(e.url), Glass.sub, 12, maxLines = 1)
        }
    }
}

@Composable private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().shadow(18.dp, shape).clip(shape).background(Glass.surface).border(1.dp, Glass.border, shape)
            .padding(top = 10.dp, bottom = 8.dp), content = content)
    }
}

@Composable private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.raised), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Glass.text, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(text, Glass.text, 15)
    }
}

@Composable private fun Text(s: String, color: androidx.compose.ui.graphics.Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, weight: FontWeight = FontWeight.Normal) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = if (size >= 20) Type.Display else Type.Sans, fontWeight = weight, fontSize = size.sp, color = color))
