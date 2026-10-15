package com.blaze.assistant.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/**
 * Blaze's own browser. Tabs are WebViews that live here, not in the screen, so Blaze can open,
 * read, click and type on pages in the background, and the Browser screen just shows the current tab.
 */
object BrowserEngine {
    class Tab(val id: Int, val web: WebView, val ctxWrap: MutableContextWrapper, val isPrivate: Boolean = false) {
        var title by mutableStateOf(if (isPrivate) "Private tab" else "New tab")
        var icon by mutableStateOf<ImageBitmap?>(null)
        /** A small picture of the page for the tab grid, taken when you leave the tab. */
        var thumb by mutableStateOf<ImageBitmap?>(null)
        internal var mask: ScriptHandler? = null
        internal var maskJs: String? = null
        var url by mutableStateOf("")
        var progress by mutableIntStateOf(100)
        var canBack by mutableStateOf(false)
        var canForward by mutableStateOf(false)
        @Volatile var loaded: CompletableDeferred<Unit> = CompletableDeferred(Unit)
        /** A JS alert/confirm the page showed while nobody was looking (answered for it, told to Blaze on the next read). */
        @Volatile var lastDialog: String? = null
    }

    /** Set when a page's renderer died (Android closed it for memory, or it crashed) and the tab was reopened. */
    @Volatile var lastCrash: String? = null

    val tabs = mutableStateListOf<Tab>()
    var current by mutableIntStateOf(0)
    var desktop by mutableStateOf(false)
    var fullView by mutableStateOf<View?>(null)
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    /** The Browser screen sets this so file pickers on pages work. */
    var filePicker: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    private var nextId = 1
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun tab(): Tab? = tabs.getOrNull(current)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        BrowserVpn.onMaskChanged = { remask() }
        BrowserVpn.init(app)
        main.post { clearPrivateData() }   // leftovers if Blaze was killed with private tabs open
    }

    // ---------- private tabs ----------
    /** Private tabs get their own WebView profile: separate cookies, storage and cache, wiped when the last one closes. */
    private const val PRIVATE_PROFILE = "blaze-private"
    val privateIsolated: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    fun privateTabs() = tabs.filter { it.isPrivate }
    fun normalTabs() = tabs.filter { !it.isPrivate }

    private fun clearPrivateData() {
        if (!privateIsolated) return
        try {
            val p = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        } catch (_: Exception) { }
    }

    // ---------- location masking ----------
    private fun shouldMask(t: Tab) = BrowserVpn.maskLocation && (t.isPrivate || BrowserVpn.on)

    private fun installMask(t: Tab) {
        try { t.mask?.remove() } catch (_: Exception) { }
        t.mask = null; t.maskJs = null
        if (!shouldMask(t)) return
        val js = LocationMask.script(BrowserVpn.maskCountry())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { t.mask = WebViewCompat.addDocumentStartJavaScript(t.web, js, setOf("*")); return } catch (_: Exception) { }
        }
        t.maskJs = js   // older WebView: injected as each page starts (best effort)
    }

    /** Re-applies masking after the VPN or the setting changed; reloads the visible page so it takes effect. */
    fun remask() {
        tabs.forEach { installMask(it) }
        tab()?.let { if (it.url.startsWith("http")) it.web.reload() }
    }

    /** Takes a small picture of the tab for the tab grid (only works while it's on screen). */
    fun snapshot(t: Tab? = tab()) {
        val w = t?.web ?: return
        if (w.width <= 0 || w.height <= 0 || !w.isAttachedToWindow) return
        try {
            val sc = 0.35f
            val b = Bitmap.createBitmap((w.width * sc).toInt().coerceAtLeast(1), (w.height * sc).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(b); c.scale(sc, sc); c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = b.asImageBitmap()
        } catch (_: Throwable) { }
    }

    // ---------- tabs ----------
    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(ctx: Context, url: String? = null, isPrivate: Boolean = false): Tab {
        init(ctx)
        val wrap = MutableContextWrapper(app)
        val w = WebView(wrap)
        // The profile must be set before the WebView is used for anything else.
        if (isPrivate && privateIsolated) { try { WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (_: Exception) { } }
        else if (isPrivate) toast("Private tabs share cookies with normal tabs on this phone. Update Android System WebView to keep them apart.")
        val t = Tab(nextId++, w, wrap, isPrivate)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            userAgentString = ua(defaultUa(w), desktop)
            if (isPrivate) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        layoutOffscreen(w)
        w.webViewClient = object : WebViewClient() {
            // v0.20.0: a dead page renderer (Android reclaims it on a 3 GB phone, or it crashes) used to take the
            // whole app down with it, and Samsung then switches Blaze's phone control off. Reopen the tab instead.
            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                val crashed = try { d.didCrash() } catch (_: Throwable) { false }
                com.blaze.assistant.device.CrashLog.write("Browser renderer ${if (crashed) "crashed" else "was closed by Android"} on ${t.url.take(80)}; tab reopened")
                main.post { reopen(t) }
                return true
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "about" || u.scheme == "data" || u.scheme == "blob") return false
                // Apps and intents: whatsapp:, tel:, mailto:, intent://, market:…
                try {
                    val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
                    app.startActivity(i)
                } catch (_: Exception) {
                    if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")?.let { v.loadUrl(it) }
                }
                return true
            }
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                t.url = url.orEmpty(); t.progress = 5
                if (t.loaded.isCompleted) t.loaded = CompletableDeferred()
                t.maskJs?.let { v.evaluateJavascript(it, null) }
            }
            override fun onPageFinished(v: WebView, url: String?) {
                t.url = url.orEmpty(); t.progress = 100; t.canBack = v.canGoBack(); t.canForward = v.canGoForward()
                t.loaded.complete(Unit)
                if (!t.isPrivate && !url.isNullOrBlank() && url != "about:blank") BrowserData.addHistory(app, v.title ?: url, url)
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                t.url = url.orEmpty(); t.canBack = v.canGoBack(); t.canForward = v.canGoForward()
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { t.progress = p }
            // In the background there's no Activity to show a page's dialog: answer it here and tell Blaze.
            override fun onJsAlert(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "alert: " + message.orEmpty().take(300); r.confirm(); return true
            }
            override fun onJsConfirm(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "confirm (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
            }
            override fun onJsPrompt(v: WebView, url: String?, message: String?, def: String?, r: android.webkit.JsPromptResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "prompt (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
            }
            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                r.confirm(); return true
            }
            override fun onReceivedTitle(v: WebView, title: String?) { t.title = title?.ifBlank { null } ?: t.url }
            override fun onReceivedIcon(v: WebView, icon: Bitmap?) { t.icon = icon?.asImageBitmap() }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean =
                filePicker?.invoke(cb, p) ?: run { cb.onReceiveValue(null); false }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { fullView = view; fullCallback = callback }
            override fun onHideCustomView() { fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null }
        }
        w.setDownloadListener { dl, userAgent, disposition, mime, _ -> download(dl, userAgent, disposition, mime) }
        installMask(t)
        tabs.add(t); current = tabs.lastIndex
        if (!url.isNullOrBlank()) load(t, url)
        return t
    }

    /** True while the Browser screen shows this tab (an Activity is attached to its dialogs). */
    private fun visible(t: Tab) = t.ctxWrap.baseContext !== app && t.web.isAttachedToWindow

    /**
     * The background browser: a WebView that isn't on screen still needs a size, or pages lay out at 0×0
     * and every element looks invisible to browser_read. Give it the phone's screen size, offscreen.
     */
    fun layoutOffscreen(w: WebView) {
        if (w.isAttachedToWindow) return
        try {
            val dm = app.resources.displayMetrics
            val wp = dm.widthPixels.coerceAtLeast(360); val hp = dm.heightPixels.coerceAtLeast(640)
            w.measure(View.MeasureSpec.makeMeasureSpec(wp, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hp, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, wp, hp)
        } catch (_: Throwable) { }
    }

    /** Replaces a tab whose renderer died with a fresh one on the same page. */
    private fun reopen(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        val url = t.url.takeIf { it.startsWith("http") }
        val keep = tabs.getOrNull(current)?.takeIf { it !== t }
        try { (t.web.parent as? ViewGroup)?.removeView(t.web); t.web.destroy() } catch (_: Throwable) { }
        tabs.removeAt(i)
        val n = newTab(app, url, t.isPrivate)
        tabs.remove(n); tabs.add(i.coerceAtMost(tabs.size), n)
        current = keep?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: tabs.indexOf(n)
        lastCrash = "The page ${url?.take(80) ?: ""} stopped (Android closed it to save memory) and was reopened; anything typed there was lost."
    }

    fun exitFull() { fullCallback?.onCustomViewHidden(); fullView = null; fullCallback = null }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        t.web.stopLoading(); t.web.destroy()
        tabs.removeAt(i)
        if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0)
        if (t.isPrivate && tabs.none { it.isPrivate }) clearPrivateData()
    }

    /** Closes every tab on one side of the tab grid. */
    fun closeAll(isPrivate: Boolean) { for (i in tabs.indices.reversed()) if (tabs[i].isPrivate == isPrivate) close(i) }

    fun closeAll() { while (tabs.isNotEmpty()) close(tabs.lastIndex) }

    /** Point a tab's dialogs (alerts, pickers) at the visible Activity, or back at the app. */
    fun attach(ctx: Context?) { tabs.forEach { it.ctxWrap.baseContext = ctx ?: app } }

    private fun defaultUa(w: WebView) = w.settings.userAgentString
    private fun ua(base: String, desk: Boolean) =
        if (desk) base.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "").replace("; wv", "") else base.replace("; wv", "")

    fun useDesktop(on: Boolean) {
        desktop = on
        tabs.forEach { t -> t.web.settings.userAgentString = ua(WebSettings.getDefaultUserAgent(app), on); t.web.settings.useWideViewPort = true }
        tab()?.web?.reload()
    }

    /** Text from the address bar → a URL (or a web search). */
    fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s) || s.startsWith("about:")) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        if (!s.contains(' ') && s.startsWith("localhost")) return "http://$s"
        return "https://www.google.com/search?q=" + Uri.encode(s)
    }

    fun load(t: Tab, input: String) {
        val u = toUrl(input)
        t.url = u; t.loaded = CompletableDeferred()
        t.web.loadUrl(u)
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) { toast("This download type isn't supported yet."); return }
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime).setTitle(name).setDescription("Downloading with Blaze")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            app.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
    }

    private fun toast(s: String) = main.post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() }

    // ---------- what Blaze uses ----------
    private suspend fun js(t: Tab, code: String): Any? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            t.web.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(try { JSONTokener(r ?: "null").nextValue() } catch (_: Exception) { r }) }
        }
    }

    private suspend fun waitLoad(t: Tab, ms: Long = 15_000) {
        withTimeoutOrNull(ms) { t.loaded.await() }
        kotlinx.coroutines.delay(500)   // let scripts render
    }

    private suspend fun ensureTab(ctx: Context): Tab = withContext(Dispatchers.Main) { tab() ?: newTab(ctx) }

    suspend fun open(ctx: Context, url: String, newTab: Boolean, isPrivate: Boolean = false): String {
        val t = withContext(Dispatchers.Main) {
            if (newTab || tabs.isEmpty() || tab()?.isPrivate != isPrivate) newTab(ctx, url, isPrivate) else tab()!!.also { load(it, url) }
        }
        waitLoad(t)
        return "Opened ${t.title.take(80)} (${t.url}).\n\n" + read(ctx, 3500)
    }

    /** Page text plus numbered interactive elements Blaze can click or type into. */
    suspend fun read(ctx: Context, max: Int = 7000): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open. Use browser_open first."
        val r = js(t, READ_JS) as? JSONObject ?: return "Couldn't read this page."
        val items = r.optJSONArray("items") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("Page: ").append(r.optString("title")).append(" — ").append(r.optString("url")).append('\n')
        sb.append(r.optString("text").take(max)).append("\n\nElements:\n")
        for (i in 0 until minOf(items.length(), 120)) sb.append(items.getString(i)).append('\n')
        notes(t, r)?.let { sb.append('\n').append(it) }
        return SecureInput.scrub(sb.toString())
    }

    /**
     * v0.27.1: the open page for "Ask Blaze about this page", as a person sees it (title, readable text, button / link /
     * field labels, pictures and their descriptions). Field values are never read; secrets are scrubbed.
     */
    suspend fun capture(ctx: Context, max: Int = 30_000): PageAsk.Capture {
        val t = withContext(Dispatchers.Main) { tab() } ?: return PageAsk.Capture.NONE
        val r = try { js(t, ASK_JS) as? JSONObject } catch (_: Exception) { null }
            ?: return PageAsk.Capture(t.title, t.url, "")
        fun list(k: String) = r.optJSONArray(k)?.let { a -> (0 until a.length()).map { SecureInput.scrub(a.optString(it)) } } ?: emptyList()
        return PageAsk.Capture(title = r.optString("title").ifBlank { t.title }, url = r.optString("url").ifBlank { t.url },
            text = SecureInput.scrub(r.optString("text").take(max)), labels = list("labels"), images = r.optInt("imgs"),
            alts = list("alts"), password = r.optBoolean("pw"))
    }

    /** Things Blaze should know about the page: a dialog it answered, a renderer restart, a site that refuses passwords. */
    private fun notes(t: Tab, r: JSONObject?): String? {
        val out = ArrayList<String>()
        lastCrash?.let { out += it; lastCrash = null }
        t.lastDialog?.let { out += "The page showed a dialog while in the background ($it)."; t.lastDialog = null }
        if (r != null) {
            val items = r.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            SignInHints.check(r.optString("text"), items)?.let { out += it }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Notes for the page as it is now (after a click or a submit). */
    private suspend fun notesNow(t: Tab): String = notes(t, js(t, READ_JS) as? JSONObject)?.let { "\n$it" }.orEmpty()

    /** Host of the page in the current tab ("" when none). */
    fun host(): String = tab()?.url?.let { SecureInput.hostOf(it) }.orEmpty()
    fun url(): String = tab()?.url.orEmpty()

    /** True when the current page says this account can't use a password. */
    suspend fun passwordRefused(): String? {
        val t = withContext(Dispatchers.Main) { tab() } ?: return null
        val r = js(t, READ_JS) as? JSONObject ?: return null
        val items = r.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return SignInHints.check(r.optString("text"), items)
    }

    /**
     * Types a value the user gave in a secure form into the page. The value comes from SecureInput,
     * never from the model, and the field is marked so browser_read never shows what's in it.
     */
    suspend fun fillSecret(f: SecureInput.Field, value: String): Boolean {
        val t = withContext(Dispatchers.Main) { tab() } ?: return false
        val sel = f.target.toIntOrNull()?.let { "document.querySelector('[data-bz=\"$it\"]')" }
            ?: f.target.takeIf { it.isNotBlank() }?.let { "bzFind(${JSONObject.quote(it)}, true)" }
            ?: "bzFirst(${JSONObject.quote(SecureInput.autoSelector(f.kind))})"
        val r = js(t, """(function(){$FIND_JS
            function bzFirst(s){var l=[].slice.call(document.querySelectorAll(s)).filter(function(e){return bzVis(e)&&!e.disabled&&!e.readOnly&&!e.hasAttribute('data-bz-secret');});return l[0]||null;}
            var e=$sel; if(!e) return 'missing'; e.scrollIntoView({block:'center'}); e.focus();
            var v=${JSONObject.quote(value)};
            var proto = e.tagName=='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto,'value').set.call(e, v);
            e.setAttribute('data-bz-secret','1');
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
            return 'ok'; })()""")
        return r == "ok"
    }

    /** Submits the form the secure fields are in (its submit button, else requestSubmit, else Enter). */
    suspend fun submitSecret(): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        js(t, """(function(){var l=document.querySelectorAll('[data-bz-secret]'); var e=l[l.length-1]; if(!e) return 'missing';
            var f=e.form; var b=f&&f.querySelector('button[type=submit],input[type=submit],button:not([type])');
            if(b){b.click();return 'ok';} if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); return 'ok'; }
            var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
            e.dispatchEvent(new KeyboardEvent('keydown',o)); e.dispatchEvent(new KeyboardEvent('keyup',o)); return 'ok'; })()""")
        kotlinx.coroutines.delay(400)
        if (t.progress < 100) waitLoad(t, 12_000) else kotlinx.coroutines.delay(800)
        return "Submitted. Now on: ${t.title.take(80)} (${t.url})" + notesNow(t)
    }

    suspend fun click(ctx: Context, n: Int?, text: String?): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val sel = if (n != null && n >= 0) "document.querySelector('[data-bz=\"$n\"]')" else "bzFind(${JSONObject.quote(text.orEmpty())})"
        val r = js(t, "(function(){$FIND_JS var e=$sel; if(!e) return 'missing'; e.scrollIntoView({block:'center'}); e.focus&&e.focus(); e.click(); return 'ok:'+(e.innerText||e.value||e.getAttribute('aria-label')||e.tagName).slice(0,60);})()")
        if (r == "missing") return "No such element. Call browser_read to get fresh numbers."
        kotlinx.coroutines.delay(300)
        if (t.progress < 100) waitLoad(t, 12_000) else kotlinx.coroutines.delay(500)
        return SecureInput.scrub("Clicked ${r.toString().removePrefix("ok:")}. Now on: ${t.title.take(80)} (${t.url})" + notesNow(t))
    }

    suspend fun type(ctx: Context, n: Int?, label: String?, text0: String, submit: Boolean): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        // {{secret:name}} placeholders: swapped for the real value here, at the last moment; the model only ever saw the placeholder.
        val secret = SecureInput.hasPlaceholder(text0)
        val text = if (!secret) text0 else SecureInput.resolve(text0, SecureInput.hostOf(t.url)).let { (v, missing) ->
            v ?: return "No value for ${missing.joinToString { SecureInput.placeholder(it) }} yet. Ask the user with browser_secure_input (it shows a secure form in the chat)." }
        val sel = if (n != null && n >= 0) "document.querySelector('[data-bz=\"$n\"]')"
            else if (!label.isNullOrBlank()) "bzFind(${JSONObject.quote(label)}, true)"
            else "(document.activeElement && /INPUT|TEXTAREA/.test(document.activeElement.tagName) ? document.activeElement : document.querySelector('input[type=search],input[type=text],input:not([type]),textarea'))"
        val r = js(t, """(function(){$FIND_JS var e=$sel; if(!e) return 'missing'; e.scrollIntoView({block:'center'}); e.focus();
            var v=${JSONObject.quote(text)};
            if (e.isContentEditable) { e.innerText = v; } else {
              var proto = e.tagName=='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto,'value').set; setter.call(e, v); }
            if (${if (secret) "true" else "false"}) e.setAttribute('data-bz-secret','1');
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
            if (${if (submit) "true" else "false"}) {
              var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
              e.dispatchEvent(new KeyboardEvent('keydown',o)); e.dispatchEvent(new KeyboardEvent('keypress',o)); e.dispatchEvent(new KeyboardEvent('keyup',o));
              if (e.form) { if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
            }
            return 'ok'; })()""")
        if (r == "missing") return "No text field found. Call browser_read to see the fields."
        if (submit) { kotlinx.coroutines.delay(400); if (t.progress < 100) waitLoad(t, 12_000) }
        return SecureInput.scrub(if (submit) "Typed and submitted. Now on: ${t.title.take(80)} (${t.url})" + notesNow(t) else "Typed \u201c${text0.take(60)}\u201d.")
    }

    suspend fun scroll(ctx: Context, down: Boolean): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        js(t, "window.scrollBy(0, ${if (down) "" else "-"}window.innerHeight*0.85)")
        kotlinx.coroutines.delay(400)
        return "Scrolled ${if (down) "down" else "up"}."
    }

    suspend fun back(ctx: Context): String = withContext(Dispatchers.Main) {
        val t = tab() ?: return@withContext "No page open."
        if (t.web.canGoBack()) { t.web.goBack(); "Went back." } else "Nothing to go back to."
    }.also { tab()?.let { waitLoad(it, 8000) } }

    fun tabList(): String = if (tabs.isEmpty()) "No tabs open." else
        tabs.mapIndexed { i, t -> "${if (i == current) "*" else " "}[$i]${if (t.isPrivate) " (private)" else ""} ${t.title.take(60)} — ${t.url}" }.joinToString("\n")

    suspend fun switchTab(i: Int): String = withContext(Dispatchers.Main) {
        if (i in tabs.indices) { current = i; "Switched to ${tabs[i].title}." } else "No tab $i."
    }

    private const val FIND_JS = """
      function bzVis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      function bzLabel(e){return ((e.innerText||'')+' '+(e.value||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('name')||'')+' '+(e.getAttribute('alt')||'')).replace(/\s+/g,' ').trim().toLowerCase();}
      function bzFind(q,field){q=q.toLowerCase();var sel=field?'input,textarea,[contenteditable=true]':'a,button,input,select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[onclick],label';
        var all=[].slice.call(document.querySelectorAll(sel)).filter(bzVis);var best=null,bs=1e9;
        all.forEach(function(e){var l=bzLabel(e);if(!l)return;var i=l.indexOf(q);if(i<0)return;var s=l.length-q.length+i;if(s<bs){bs=s;best=e;}});return best;}
    """

    private const val ASK_JS = """(function(){
      function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      var labels=[];
      [].slice.call(document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab]')).filter(vis).slice(0,150).forEach(function(e){
        var tag=e.tagName.toLowerCase(), field=(tag=='input'||tag=='textarea'||tag=='select');
        var l=(field?'':(e.innerText||''))||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||'';
        l=l.replace(/\s+/g,' ').trim().slice(0,60); if(!l) return;
        labels.push(field?'Field: '+l:l);
      });
      var imgs=[].slice.call(document.querySelectorAll('img,[role=img]')).filter(function(e){if(!vis(e))return false;var r=e.getBoundingClientRect();return r.width>=60&&r.height>=60;});
      var alts=[]; imgs.forEach(function(e){var a=(e.getAttribute('alt')||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim(); if(a) alts.push(a.slice(0,100));});
      var pw=[].slice.call(document.querySelectorAll('input[type=password]')).some(vis);
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim();
      return {title:document.title,url:location.href,text:text,labels:labels,imgs:imgs.length,alts:alts.slice(0,40),pw:pw};
    })()"""

    private const val READ_JS = """(function(){
      function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      var els=[].slice.call(document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[onclick],[contenteditable=true]')).filter(vis);
      var items=[];
      els.slice(0,150).forEach(function(e,i){ e.setAttribute('data-bz',i);
        var tag=e.tagName.toLowerCase(), type=e.getAttribute('type')||'';
        var hide = type=='password' || e.hasAttribute('data-bz-secret');
        var label=(e.innerText||(hide?(e.value?'(filled, hidden)':''):e.value)||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||e.getAttribute('alt')||'').replace(/\s+/g,' ').trim().slice(0,80);
        var kind = tag=='a'?'link':(tag=='input'||tag=='textarea'||e.isContentEditable)?('field'+(type?':'+type:'')):tag=='select'?'select':'button';
        if (tag=='input' && /checkbox|radio/.test(type)) kind=type+(e.checked?' (checked)':'');
        if (!label && kind=='link') return;
        items.push('['+i+'] '+kind+' "'+label+'"'+(tag=='a'&&e.href&&e.href.indexOf('javascript')!=0?' → '+e.href.slice(0,90):''));
      });
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim();
      return {title:document.title,url:location.href,text:text,items:items};
    })()"""
}

/** Bookmarks and history, kept in filesDir/browser.json. */
object BrowserData {
    data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())
    private fun file(ctx: Context) = File(ctx.filesDir, "browser.json")
    private var cache: JSONObject? = null

    private fun data(ctx: Context): JSONObject = cache ?: try { JSONObject(file(ctx).readText()) } catch (_: Exception) {
        JSONObject().put("bookmarks", JSONArray()).put("history", JSONArray()) }.also { cache = it }

    private fun save(ctx: Context) { try { file(ctx).writeText(data(ctx).toString()) } catch (_: Exception) { } }

    private fun list(ctx: Context, k: String): List<Entry> {
        val a = data(ctx).optJSONArray(k) ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.optString("t"), it.optString("u"), it.optLong("at")) }
    }
    private fun put(ctx: Context, k: String, l: List<Entry>) {
        data(ctx).put(k, JSONArray().apply { l.forEach { put(JSONObject().put("t", it.title).put("u", it.url).put("at", it.time)) } }); save(ctx)
    }

    fun bookmarks(ctx: Context) = list(ctx, "bookmarks")
    fun history(ctx: Context) = list(ctx, "history")
    fun isBookmarked(ctx: Context, url: String) = bookmarks(ctx).any { it.url == url }
    fun toggleBookmark(ctx: Context, title: String, url: String) {
        val b = bookmarks(ctx).toMutableList()
        if (b.removeAll { it.url == url }.not()) b.add(0, Entry(title.ifBlank { url }, url))
        put(ctx, "bookmarks", b)
    }
    fun addHistory(ctx: Context, title: String, url: String) {
        val h = history(ctx).filter { it.url != url }.toMutableList()
        h.add(0, Entry(title, url)); put(ctx, "history", h.take(300))
    }
    fun clearHistory(ctx: Context) = put(ctx, "history", emptyList())
}
