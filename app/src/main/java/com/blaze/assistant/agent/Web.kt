package com.blaze.assistant.agent

import com.blaze.assistant.connect.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Keyless web search (DuckDuckGo HTML) and page reading, so Blaze can look things up without opening a browser. */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    suspend fun search(q: String, n: Int = 6): String = withContext(Dispatchers.IO) {
        val (code, html) = try {
            Http.req("GET", "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), headers = mapOf("User-Agent" to UA), timeoutMs = 15_000)
        } catch (e: Exception) { return@withContext "Error: search failed (${e.message})" }
        if (code !in 200..299) return@withContext "Error: search returned HTTP $code"
        val blocks = html.split("class=\"result__body").drop(1)
        val out = StringBuilder()
        var i = 0
        for (b in blocks) {
            val link = Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").find(b) ?: continue
            var url = link.groupValues[1]
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.contains("duckduckgo.com/y.js")) continue
            val title = strip(link.groupValues[2])
            val snip = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").find(b)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            out.append("${++i}. $title\n   $url\n   $snip\n")
            if (i >= n) break
        }
        if (i == 0) "No results for \"$q\"." else out.toString()
    }

    suspend fun fetch(url: String, max: Int = 9000): String = withContext(Dispatchers.IO) {
        val (code, body) = try { Http.req("GET", url, headers = mapOf("User-Agent" to UA, "Accept" to "text/html,application/json,text/plain"), timeoutMs = 20_000, strict = false) }
            catch (e: Exception) { return@withContext "Error: couldn't open $url (${e.message})" }
        if (code !in 200..299) return@withContext "Error: HTTP $code from $url"
        val t = body.trimStart()
        val text = if (t.startsWith("{") || t.startsWith("[")) t else {
            val titled = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            val main = body.replace(Regex("<(script|style|nav|footer|header|svg|noscript)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<(br|p|div|li|h[1-6]|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            (if (titled.isNotBlank()) "# $titled\n" else "") + strip(main).lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n")
        }
        if (text.length > max) text.take(max) + "\n…(cut, ${text.length} chars total)" else text
    }

    private fun strip(h: String) = h.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("[ \\t]+"), " ").trim()

    val names = setOf("web_search", "fetch_page")
    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")
            .put("description", "Search the web (no browser needed). Returns titles, links and snippets. Use for news, facts, prices, how-tos.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "fetch_page")
            .put("description", "Read a web page or JSON API as plain text.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("url", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("url")))))
    )
}
