package com.blaze.assistant.mediaapps.youtube

import com.blaze.assistant.mediaapps.store.StoreError
import com.blaze.assistant.mediaapps.store.StorePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.30.1: talks to YouTube's InnerTube API directly (no web view, no API key, no third-party extractor).
 *
 * Search uses the WEB client. Streams come from clients whose responses carry plain stream urls (no signature
 * deciphering, so no JavaScript): ANDROID_VR first, then IOS, then ANDROID. YouTube changes these from time to time;
 * when every client is refused the Store says so instead of failing silently. Known limits (README / STATUS):
 * age-restricted and members-only videos, and phones/IPs YouTube flags as bots ("Sign in to confirm…"), can't be fetched.
 */
object YtClient {
    private const val API = "https://www.youtube.com/youtubei/v1/"
    const val VIDEOS_ONLY = "EgIQAQ%3D%3D"   // search filter: type = video

    data class Client(val name: String, val version: String, val ua: String, val extra: Map<String, Any>, val id: Int)

    val clients = listOf(
        Client("ANDROID_VR", "1.60.19", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"), 28),
        Client("IOS", "20.10.4", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"), 5),
        Client("ANDROID", "19.44.38", "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11"), 3),
    )

    /** The user-agent a stream url was issued for; googlevideo checks it on some clients. */
    @Volatile var lastUa: String = clients[0].ua
        private set

    private fun post(endpoint: String, body: JSONObject, ua: String, clientId: Int = 1, version: String = ""): String {
        val c = URL(API + endpoint + "?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("X-YouTube-Client-Name", clientId.toString())
            if (version.isNotEmpty()) c.setRequestProperty("X-YouTube-Client-Version", version)
            c.setRequestProperty("Origin", "https://www.youtube.com")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw StoreError(if (code == 429 || text.contains("Sorry")) "YouTube is limiting requests from this network. Try again in a while." else "YouTube answered $code", code)
            return text
        } finally { c.disconnect() }
    }

    
    private fun context(name: String, version: String, extra: Map<String, Any> = emptyMap()): JSONObject {
        val client = JSONObject().put("clientName", name).put("clientVersion", version).put("hl", "en").put("gl", "US")
        extra.forEach { (k, v) -> client.put(k, v) }
        return JSONObject().put("client", client)
    }

    private const val WEB_VERSION = "2.20250101.00.00"
    private const val WEB_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** One page of video results (live streams left out). Pass [continuation] from the last page for more. */
    suspend fun search(query: String, continuation: String = ""): StorePage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", context("WEB", WEB_VERSION))
        if (continuation.isNotEmpty()) body.put("continuation", continuation) else body.put("query", query.trim()).put("params", java.net.URLDecoder.decode(VIDEOS_ONLY, "UTF-8"))
        YtParse.search(post("search", body, WEB_UA, 1, WEB_VERSION))
    }

    /** Stream urls for [videoId], trying each client until one gives downloadable formats. */
    suspend fun streams(videoId: String): YtPlayer = withContext(Dispatchers.IO) {
        var last: YtPlayer? = null
        var err: Exception? = null
        for (cl in clients) {
            try {
                val body = JSONObject().put("context", context(cl.name, cl.version, cl.extra)).put("videoId", videoId)
                    .put("contentCheckOk", true).put("racyCheckOk", true)
                val s = YtParse.player(post("player", body, cl.ua, cl.id, cl.version))
                if (s.status == "OK" && s.formats.isNotEmpty()) { lastUa = cl.ua; return@withContext s }
                last = s
            } catch (e: Exception) { err = e }
        }
        last?.let { s ->
            val why = s.reason.ifBlank { if (s.isLive) "Live streams can't be downloaded." else "YouTube didn't offer this video." }
            throw StoreError(why)
        }
        throw err ?: StoreError("YouTube didn't answer.")
    }
}
