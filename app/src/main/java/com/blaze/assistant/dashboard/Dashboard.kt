package com.blaze.assistant.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatStore
import com.blaze.assistant.agent.IntentCache
import com.blaze.assistant.agent.Learning
import com.blaze.assistant.agent.Memory
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.Usage
import com.blaze.assistant.device.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Blaze's local dashboard: a tiny web server on the phone (default 127.0.0.1:8787) with a page to
 * chat with Blaze and see analytics. "Share on Wi-Fi" lets a laptop on the same network open it,
 * protected by a token in the link.
 */
object Dashboard {
    const val PORT = 8787
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val running = MutableStateFlow(false)
    private lateinit var app: Context

    val token: String get() = Settings.str("dash_token").ifBlank {
        val t = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        Settings.setStr("dash_token", t); t
    }
    var lan: Boolean
        get() = Settings.bool("dash_lan", false)
        set(v) = Settings.setBool("dash_lan", v)

    fun localUrl() = "http://127.0.0.1:$PORT/?t=$token"
    fun lanUrl(): String? = wifiIp()?.let { "http://$it:$PORT/?t=$token" }

    private fun wifiIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    @Synchronized fun start(ctx: Context) {
        app = ctx.applicationContext
        if (server != null) return
        val s = try { ServerSocket(PORT, 16, if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")) }
            catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("dashboard start", e); return }
        server = s; running.value = true
        scope.launch {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                scope.launch { try { handle(c) } catch (_: Exception) { } finally { runCatching { c.close() } } }
            }
        }
    }

    @Synchronized fun stop() { runCatching { server?.close() }; server = null; running.value = false }
    fun restart(ctx: Context) { stop(); start(ctx) }

    fun open(ctx: Context) {
        start(ctx)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(localUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun handle(c: Socket) {
        c.soTimeout = 300_000
        val r = BufferedReader(InputStreamReader(c.getInputStream()))
        val first = r.readLine() ?: return
        val (method, target) = first.split(' ').let { (it.getOrNull(0) ?: "GET") to (it.getOrNull(1) ?: "/") }
        val headers = HashMap<String, String>()
        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; l.indexOf(':').takeIf { it > 0 }?.let { headers[l.substring(0, it).lowercase()] = l.substring(it + 1).trim() } }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) CharArray(len).let { var n = 0; while (n < len) { val k = r.read(it, n, len - n); if (k < 0) break; n += k }; String(it, 0, n) } else ""
        val path = target.substringBefore('?')
        val q = target.substringAfter('?', "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            .associate { Uri.decode(it[0]) to Uri.decode(it[1]) }
        val out = c.getOutputStream()
        val tok = q["t"] ?: headers["x-blaze-token"]
        if (tok != token) return send(out, 401, "text/plain", "Blaze dashboard: open the link from Blaze (Settings → Dashboard).")
        when {
            path == "/" -> send(out, 200, "text/html; charset=utf-8", Page.html)
            path == "/api/stats" -> send(out, 200, "application/json", stats().toString())
            path == "/api/chats" -> send(out, 200, "application/json", JSONArray().apply {
                ChatStore.list().take(60).forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated).put("preview", it.preview)) } }.toString())
            path == "/api/chat" -> {
                val m = Agent.messagesOf(q["id"].orEmpty()) ?: emptyList()
                send(out, 200, "application/json", JSONArray().apply { m.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text).put("meta", it.meta)) } }.toString())
            }
            path == "/api/send" && method == "POST" -> {
                val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val (id, reply) = runBlocking { Agent.askIn(o.optString("chat").ifBlank { null }, o.optString("text")) }
                send(out, 200, "application/json", JSONObject().put("chat", id).put("reply", reply).toString())
            }
            else -> send(out, 404, "text/plain", "Not found")
        }
    }

    private fun stats(): JSONObject {
        val d = DeviceStatus.snap(app)
        val t = Usage.today(); val m = Usage.month()
        return JSONObject()
            .put("version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrDefault(""))
            .put("model", Settings.model).put("provider", Settings.provider.name)
            .put("today", JSONObject().put("tokens", t.input + t.output).put("cost", t.cost).put("calls", t.calls))
            .put("month", JSONObject().put("tokens", m.input + m.output).put("cost", m.cost).put("calls", m.calls))
            .put("daily", JSONArray(Usage.daily(14)))
            .put("chats", ChatStore.list().size).put("memories", Memory.facts.value.size).put("skills", Learning.skills.value.size)
            .put("instant", IntentCache.size()).put("jobs", com.blaze.assistant.terminal.Terminal.jobCount())
            .put("device", JSONObject().put("battery", d.battery).put("charging", d.charging).put("temp", d.tempC)
                .put("storageFree", d.freeStorageGb).put("storageTotal", d.totalStorageGb).put("ramFree", d.freeRamMb).put("ramTotal", d.totalRamMb).put("network", d.network))
            .put("warnings", JSONArray(DeviceStatus.warnings(d)))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }
}
