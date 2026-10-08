package com.blaze.assistant.connect

import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Remote MCP servers over Streamable HTTP (JSON-RPC 2.0). Their tools appear to the agent as
 * mcp_<server>_<tool>. Servers are stored in pref "mcp_servers"; bearer tokens are encrypted.
 */
object Mcp {
    data class Server(val id: String, val name: String, val url: String, val enabled: Boolean)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    private val sessions = ConcurrentHashMap<String, String>()
    private val toolCache = ConcurrentHashMap<String, List<JSONObject>>()   // server id → raw MCP tool defs
    private var rpcId = 1

    fun load() {
        _servers.value = try { JSONArray(Settings.str("mcp_servers", "[]")).let { a -> (0 until a.length()).map {
            val o = a.getJSONObject(it); Server(o.getString("id"), o.optString("name"), o.getString("url"), o.optBoolean("enabled", true)) } } }
        catch (_: Exception) { emptyList() }
    }

    private fun save(l: List<Server>) {
        _servers.value = l
        Settings.setStr("mcp_servers", JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("enabled", it.enabled) }).toString())
    }

    fun add(name: String, url: String, token: String) {
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "").take(12).ifEmpty { "srv" + (_servers.value.size + 1) }
        save(_servers.value.filterNot { it.id == id } + Server(id, name.trim(), url.trim(), true))
        Settings.setSecret("mcp_tok_$id", token.trim())
        toolCache.remove(id); sessions.remove(id)
    }

    fun remove(id: String) { save(_servers.value.filterNot { it.id == id }); Settings.setSecret("mcp_tok_$id", ""); toolCache.remove(id) }
    fun toggle(id: String) = save(_servers.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })

    private fun setStatus(id: String, s: String) { _status.value = _status.value + (id to s) }

    private fun rpc(s: Server, method: String, params: JSONObject?, notify: Boolean = false): JSONObject? {
        val c = URL(s.url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 60_000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
        Settings.secret("mcp_tok_${s.id}").takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        sessions[s.id]?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) msg.put("params", params)
        val myId = synchronized(this) { rpcId++ }
        if (!notify) msg.put("id", myId)
        c.outputStream.use { it.write(msg.toString().toByteArray()) }
        val code = c.responseCode
        c.getHeaderField("Mcp-Session-Id")?.let { sessions[s.id] = it }
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect(); throw Exception("HTTP $code ${err.take(150)}")
        }
        if (notify) { c.disconnect(); return null }
        val type = c.contentType.orEmpty()
        val text = c.inputStream.bufferedReader().use { r ->
            if (type.contains("event-stream")) {
                // Read SSE events until the response with our id arrives.
                var found: String? = null
                for (line in r.lineSequence()) {
                    if (!line.startsWith("data:")) continue
                    val d = line.removePrefix("data:").trim()
                    val o = try { JSONObject(d) } catch (_: Exception) { continue }
                    if (o.optInt("id", -1) == myId) { found = d; break }
                }
                found ?: ""
            } else r.readText()
        }
        c.disconnect()
        val o = JSONObject(text)
        o.optJSONObject("error")?.let { throw Exception(it.optString("message")) }
        return o.optJSONObject("result")
    }

    private fun initialize(s: Server) {
        rpc(s, "initialize", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Blaze").put("version", "1.7")))
        try { rpc(s, "notifications/initialized", null, notify = true) } catch (_: Exception) { }
    }

    /** Connects and lists a server's tools (cached until it changes). */
    suspend fun refresh(s: Server): String = withContext(Dispatchers.IO) {
        try {
            sessions.remove(s.id)
            initialize(s)
            val l = rpc(s, "tools/list", JSONObject())?.optJSONArray("tools") ?: JSONArray()
            toolCache[s.id] = (0 until l.length()).map { l.getJSONObject(it) }
            "${l.length()} tools".also { setStatus(s.id, it) }
        } catch (e: Exception) { ("Error: " + e.message).also { setStatus(s.id, it) } }
    }

    private fun toolName(sid: String, t: String) = ("mcp_${sid}_" + t.replace(Regex("[^A-Za-z0-9_-]"), "_")).take(64)

    /** Tools of enabled servers, in OpenAI format. Fetches lists not yet cached (quickly). */
    suspend fun tools(): List<JSONObject> {
        val on = _servers.value.filter { it.enabled }
        for (s in on) if (toolCache[s.id] == null) withTimeoutOrNull(6000) { refresh(s) }
        return on.flatMap { s -> toolCache[s.id].orEmpty().map { t ->
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", toolName(s.id, t.optString("name")))
                .put("description", ("[${s.name}] " + t.optString("description")).take(900))
                .put("parameters", t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())))
        } }
    }

    fun handles(name: String) = name.startsWith("mcp_")

    suspend fun call(name: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        for (s in _servers.value.filter { it.enabled }) {
            val t = toolCache[s.id]?.firstOrNull { toolName(s.id, it.optString("name")) == name } ?: continue
            return@withContext try {
                if (sessions[s.id] == null) initialize(s)
                val r = try { rpc(s, "tools/call", JSONObject().put("name", t.optString("name")).put("arguments", args)) }
                    catch (e: Exception) { if (e.message?.contains("404") == true || e.message?.contains("session", true) == true) { sessions.remove(s.id); initialize(s)
                        rpc(s, "tools/call", JSONObject().put("name", t.optString("name")).put("arguments", args)) } else throw e }
                val content = r?.optJSONArray("content") ?: JSONArray()
                val out = (0 until content.length()).joinToString("\n") { i -> content.getJSONObject(i).let {
                    if (it.optString("type") == "text") it.optString("text") else "[${it.optString("type")} content]" } }
                (if (r?.optBoolean("isError") == true) "Tool error: " else "") + out.take(8000).ifEmpty { r?.optJSONObject("structuredContent")?.toString()?.take(8000) ?: "Done." }
            } catch (e: Exception) { "MCP error: ${e.message}" }
        }
        "That MCP tool isn't available any more."
    }
}
