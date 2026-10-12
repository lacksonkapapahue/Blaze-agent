package com.blaze.assistant.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The browser's VPN. Your WireGuard configs (one per country) run through wgproxy, a user-space
 * WireGuard tunnel shipped as libwgproxy.so, which serves an HTTP proxy on 127.0.0.1. Only Blaze's
 * browser WebViews use it (ProxyController), so chats with your model don't go through the VPN and
 * Android shows no VPN prompt.
 *
 * Kill switch: while the VPN is on, the browser never goes direct. If the tunnel drops or fails to
 * start, pages fail to load instead of loading from your real IP, until you pick a working location
 * or switch the VPN off.
 */
object BrowserVpn {
    data class Location(val id: String, val name: String, val country: String?, val conf: String) {
        val label: String get() = Countries.byCode(country)?.name ?: name
        val flag: String get() = Countries.flag(country.orEmpty())
    }

    enum class Status { OFF, CONNECTING, ON, FAILED }

    val locations = mutableStateListOf<Location>()
    var status by mutableStateOf(Status.OFF); private set
    var selected by mutableStateOf<String?>(null); private set
    var exitIp by mutableStateOf(""); private set
    /** Country the tunnel really exits in, from Cloudflare's trace through the tunnel. */
    var exitCountry by mutableStateOf<String?>(null); private set
    var error by mutableStateOf(""); private set
    /** Mask location (geolocation, time zone, language, WebRTC) in private tabs, and in every tab while the VPN is on. */
    var maskLocation by mutableStateOf(true); private set

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val mainExec = Executor { main.post(it) }
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var port = 0
    /** Bumped when the tunnel changes, so a dying old process doesn't flag the new one as failed. */
    @Volatile private var gen = 0
    /** Called on the main thread when masking or the exit country changes, so tabs re-install their script. */
    var onMaskChanged: (() -> Unit)? = null

    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    val on: Boolean get() = status == Status.ON || status == Status.CONNECTING || status == Status.FAILED
    fun current(): Location? = locations.firstOrNull { it.id == selected }

    /** The country pages should see: the tunnel's real exit, else the location's country. Null with the VPN off. */
    fun maskCountry(): Countries.Country? = if (status != Status.ON) null else Countries.byCode(exitCountry ?: current()?.country)

    private fun file() = File(app.filesDir, "browser_vpn.json")

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        try {
            val o = JSONObject(file().readText())
            maskLocation = o.optBoolean("mask", true)
            val a = o.optJSONArray("locations") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                locations += Location(it.getString("id"), it.optString("name"), it.optString("country").ifBlank { null }, it.getString("conf"))
            }
            // The VPN comes back on after a restart if it was on, so the browser never silently goes direct.
            val sel = o.optString("selected").ifBlank { null }
            if (o.optBoolean("enabled") && sel != null && locations.any { it.id == sel }) {
                selected = sel; status = Status.CONNECTING
                if (supported) ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule("127.0.0.1:1").build(), mainExec) { }
                connect(sel)
            }
        } catch (_: Exception) { }
    }

    private fun save(enabled: Boolean = on) {
        try {
            val a = JSONArray()
            locations.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("country", it.country.orEmpty()).put("conf", it.conf)) }
            file().writeText(JSONObject().put("enabled", enabled).put("selected", selected.orEmpty()).put("mask", maskLocation).put("locations", a).toString())
        } catch (_: Exception) { }
    }

    fun setMask(v: Boolean) { maskLocation = v; save(); onMaskChanged?.invoke() }

    /** Adds a config from text. Returns the new location, or throws with a plain-words reason. */
    fun add(name: String, text: String): Location {
        val cfg = WireGuardConfig.parse(text)
        val country = WireGuardConfig.guessCountry(name, cfg.peers.first().endpointHost)
        val clean = name.substringAfterLast('/').removeSuffix(".conf").ifBlank { cfg.peers.first().endpointHost }
        val loc = Location("wg" + System.currentTimeMillis().toString(36) + locations.size, clean, country, text)
        locations += loc; save()
        return loc
    }

    /** Imports .conf files, or a .zip of them (what Proton and Mullvad download). Returns (added, problems). */
    fun import(name: String, bytes: ByteArray): Pair<List<Location>, List<String>> {
        val added = mutableListOf<Location>(); val bad = mutableListOf<String>()
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".conf", true)) {
                        val t = z.readBytes().toString(Charsets.UTF_8)
                        try { added += add(e.name, t) } catch (x: Exception) { bad += "${e.name.substringAfterLast('/')}: ${x.message}" }
                    }
                    e = z.nextEntry
                }
            }
            if (added.isEmpty() && bad.isEmpty()) bad += "$name has no .conf files in it."
        } else try { added += add(name, bytes.toString(Charsets.UTF_8)) } catch (x: Exception) { bad += "$name: ${x.message}" }
        return added to bad
    }

    fun rename(id: String, country: String?) {
        val i = locations.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        locations[i] = locations[i].copy(country = country); save()
    }

    fun remove(id: String) {
        if (selected == id) disconnect()
        locations.removeAll { it.id == id }; save()
    }

    /** Find a location by country name/code or location name. */
    fun find(q: String): Location? {
        val c = Countries.find(q)
        return locations.firstOrNull { c != null && it.country == c.code }
            ?: locations.firstOrNull { it.name.equals(q, true) } ?: locations.firstOrNull { it.name.contains(q, true) }
    }

    fun connect(id: String) { scope.launch { connectNow(id) } }

    /** Starts (or switches) the tunnel and waits until it's up and checked. Returns a plain-words result. */
    suspend fun connectNow(id: String): String = lock.withLock {
        val loc = locations.firstOrNull { it.id == id } ?: return "No such VPN location."
        if (!supported) { error = "This phone's Android System WebView is too old for the browser VPN. Update it in the Play Store."; return error }
        val g = ++gen
        stopProc()
        withContext(Dispatchers.Main) { selected = id; status = Status.CONNECTING; error = ""; exitIp = ""; exitCountry = null }
        setProxy(1)   // kill switch while connecting: nothing goes direct
        save(true)
        try {
            val cfg = WireGuardConfig.parse(loc.conf)
            val json = cfg.toProxyJson { host -> resolve(host) }
            val bin = File(app.applicationInfo.nativeLibraryDir, "libwgproxy.so")
            if (!bin.exists()) throw IllegalStateException("The VPN engine is missing from this build.")
            val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            proc = p; stdin = p.outputStream
            p.outputStream.write((json + "\n").toByteArray()); p.outputStream.flush()
            val out = BufferedReader(InputStreamReader(p.inputStream))
            val first = withTimeoutLine(out, 10_000) ?: throw IllegalStateException("The VPN engine didn't start.")
            if (first.startsWith("ERROR")) throw IllegalArgumentException(first.removePrefix("ERROR").trim())
            port = first.removePrefix("READY").trim().toIntOrNull() ?: throw IllegalStateException("The VPN engine said: $first")
            // Watch for the tunnel dying; keep the kill switch on if it does.
            Thread {
                try { while (out.readLine() != null) { } } catch (_: Exception) { }
                if (gen == g && status != Status.OFF) main.post { status = Status.FAILED; error = "The VPN tunnel stopped. Pages won't load until you reconnect or turn the VPN off." }
            }.apply { isDaemon = true }.start()
            setProxy(port)
            val (ip, cc) = trace(port)
            withContext(Dispatchers.Main) { exitIp = ip; exitCountry = cc; status = Status.ON }
            // The exit check is the truth about the country; fix the label if the guess was wrong or missing.
            if (cc != null && cc != loc.country) rename(loc.id, cc)
            withContext(Dispatchers.Main) { onMaskChanged?.invoke() }
            val c = Countries.byCode(cc)
            "VPN on: browsing from ${c?.name ?: cc ?: loc.label} (${ip.ifBlank { "IP hidden" }})."
        } catch (e: Exception) {
            if (gen == g) {
                stopProc()
                val why = when {
                    e is java.net.SocketTimeoutException || e.message?.contains("timed out", true) == true || e.message?.contains("tunnel", true) == true ->
                        "The VPN server didn't answer. The config may be expired or revoked, or this network blocks WireGuard."
                    else -> e.message ?: "The VPN couldn't connect."
                }
                withContext(Dispatchers.Main) { status = Status.FAILED; error = why; onMaskChanged?.invoke() }
                setProxy(1)
            }
            "VPN failed: $error The browser stays blocked (kill switch) until you pick another location or turn the VPN off."
        }
    }

    /** Turns the VPN off: the browser goes direct again. Safe to call from any thread. */
    fun disconnect() {
        gen++
        main.post {
            status = Status.OFF; exitIp = ""; exitCountry = null; error = ""
            if (supported) ProxyController.getInstance().clearProxyOverride(mainExec) { }
            onMaskChanged?.invoke()
        }
        scope.launch { lock.withLock { stopProc() }; save(false) }
    }

    private fun stopProc() {
        try { stdin?.close() } catch (_: Exception) { }
        proc?.let { p -> try { if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy() } catch (_: Exception) { p.destroy() } }
        proc = null; stdin = null; port = 0
    }

    /** Port 1 is the kill switch: nothing listens there, so pages fail instead of going direct. */
    private suspend fun setProxy(port: Int) = withContext(Dispatchers.Main) {
        if (!supported) return@withContext
        val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(cfg, mainExec) { }
    }

    private fun resolve(host: String): String {
        if (Regex("^[0-9.]+$").matches(host) || host.contains(':')) return host
        val all = InetAddress.getAllByName(host)
        return (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress ?: host
    }

    private fun withTimeoutLine(r: BufferedReader, ms: Long): String? {
        var line: String? = null
        val t = Thread { line = try { r.readLine() } catch (_: Exception) { null } }
        t.start(); t.join(ms)
        return line
    }

    /** Asks Cloudflare, through the tunnel, which IP and country the browser now appears from. */
    private fun trace(port: Int): Pair<String, String?> {
        val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as java.net.HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 12_000
        try {
            val m = c.inputStream.bufferedReader().readText().lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return m["ip"].orEmpty() to m["loc"]?.takeIf { it.length == 2 && it != "XX" && it != "T1" }
        } finally { c.disconnect() }
    }

    fun statusText(): String = when (status) {
        Status.OFF -> "VPN off." + if (locations.isEmpty()) " No locations yet: import a WireGuard config in the browser (⋯ › VPN & privacy)." else
            " Locations: " + locations.joinToString { it.label }
        Status.CONNECTING -> "VPN connecting to ${current()?.label}…"
        Status.ON -> "VPN on: ${Countries.byCode(exitCountry)?.name ?: current()?.label} · $exitIp. Location masking ${if (maskLocation) "on" else "off"}."
        Status.FAILED -> "VPN failed ($error). Browser blocked by the kill switch."
    }
}
