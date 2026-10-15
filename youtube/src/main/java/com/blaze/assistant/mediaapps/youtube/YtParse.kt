package com.blaze.assistant.mediaapps.youtube

import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StorePage
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.1: parses YouTube's own app API (InnerTube, the JSON the YouTube apps use) for the Store tabs of Blaze Music
 * and Blaze Video. Blaze never shows a YouTube web page: search results become a plain list of thumbnail + title +
 * channel + duration, and the player response becomes a list of downloadable streams. Pure (YtParseTest).
 *
 * Lives in mediaapps/youtube/ only, so the whole YouTube source can be deleted (see StoreModel.kt).
 * Written for Blaze (no third-party extractor code): NewPipeExtractor is GPLv3, which Blaze-agent's licence can't take.
 */
/** A player response: the generic [StoreDetail] plus YouTube's playability verdict. */
data class YtPlayer(
    val detail: StoreDetail,
    val status: String,        // OK | LOGIN_REQUIRED | UNPLAYABLE | ERROR | …
    val reason: String,
    val expiresInSec: Int,
    val isLive: Boolean,
) {
    val formats: List<StoreFormat> get() = detail.formats
}

object YtParse {
    /** The smallest standard thumbnail (320×180): cheap for low-RAM phones, always there. */
    fun thumbFor(id: String, big: Boolean = false) = "https://i.ytimg.com/vi/$id/" + (if (big) "hqdefault.jpg" else "mqdefault.jpg")

    fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return o.optString("content")
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    /** First search page (twoColumnSearchResultsRenderer) or a continuation page (appendContinuationItemsAction). */
    fun search(json: String): StorePage {
        val root = JSONObject(json)
        val sections = ArrayList<JSONObject>()
        root.optJSONObject("contents")?.optJSONObject("twoColumnSearchResultsRenderer")?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(sections::add) }
        root.optJSONArray("onResponseReceivedCommands")?.let { cmds ->
            for (i in 0 until cmds.length()) {
                val items = cmds.optJSONObject(i)?.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems") ?: continue
                for (j in 0 until items.length()) items.optJSONObject(j)?.let(sections::add)
            }
        }
        val out = ArrayList<StoreItem>()
        var cont = ""
        val seen = HashSet<String>()
        for (sec in sections) {
            sec.optJSONObject("continuationItemRenderer")?.let { cont = continuationToken(it).ifEmpty { cont } }
            val list = sec.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
            for (k in 0 until list.length()) {
                val v = list.optJSONObject(k)?.optJSONObject("videoRenderer") ?: continue
                video(v)?.let { if (seen.add(it.id)) out += it }
            }
        }
        return StorePage(out, cont)
    }

    private fun continuationToken(c: JSONObject): String =
        c.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token").orEmpty()

    /** One videoRenderer; null for live streams, upcoming premieres and anything without an id or a duration. */
    fun video(v: JSONObject): StoreItem? {
        val id = v.optString("videoId")
        if (id.length != 11) return null
        val dur = text(v.optJSONObject("lengthText"))
        val sec = com.blaze.assistant.mediaapps.store.StoreText.parseClock(dur)
        if (sec <= 0) return null   // live / upcoming: nothing to download
        val owner = text(v.optJSONObject("ownerText")).ifEmpty { text(v.optJSONObject("longBylineText")) }
        return StoreItem(YouTubeSource.ID, id, text(v.optJSONObject("title")), owner, sec, dur, thumbFor(id), "YouTube", "", text(v.optJSONObject("shortViewCountText")))
    }

    private val codecsRe = Regex("""codecs="([^"]*)"""")

    fun format(f: JSONObject): StoreFormat? {
        val url = f.optString("url")
        if (url.isEmpty()) return null   // signatureCipher-only formats need the web player's JS; we use clients that don't
        if (f.optBoolean("isDrc")) return null
        val track = f.optJSONObject("audioTrack")
        if (track != null && track.has("audioIsDefault") && !track.optBoolean("audioIsDefault")) return null   // dubbed tracks
        val mimeFull = f.optString("mimeType")
        val mime = mimeFull.substringBefore(';').trim()
        val codecs = codecsRe.find(mimeFull)?.groupValues?.get(1).orEmpty()
        val isAudioMime = mime.startsWith("audio/")
        val isVideoMime = mime.startsWith("video/")
        if (!isAudioMime && !isVideoMime) return null
        val codecList = codecs.split(',').map { it.trim() }
        val videoHasAudio = isVideoMime && (codecList.size > 1 || f.has("audioQuality"))
        return StoreFormat(
            key = f.optInt("itag").toString(),
            url = url,
            mime = mime,
            codecs = codecs,
            bitrate = f.optInt("averageBitrate").takeIf { it > 0 } ?: f.optInt("bitrate"),
            contentLength = f.optString("contentLength").toLongOrNull() ?: 0L,
            height = f.optInt("height"),
            qualityLabel = f.optString("qualityLabel"),
            hasAudio = isAudioMime || videoHasAudio,
            hasVideo = isVideoMime,
            fps = f.optInt("fps"),
        )
    }

    fun player(json: String): YtPlayer {
        val root = JSONObject(json)
        val ps = root.optJSONObject("playabilityStatus")
        val vd = root.optJSONObject("videoDetails")
        val sd = root.optJSONObject("streamingData")
        val fmts = ArrayList<StoreFormat>()
        fun take(a: JSONArray?) { if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { format(it) }?.let(fmts::add) }
        take(sd?.optJSONArray("formats"))
        take(sd?.optJSONArray("adaptiveFormats"))
        val id = vd?.optString("videoId").orEmpty()
        return YtPlayer(
            detail = StoreDetail(id, vd?.optString("title").orEmpty(), vd?.optString("author").orEmpty(),
                vd?.optString("lengthSeconds")?.toIntOrNull() ?: 0, if (id.isNotEmpty()) thumbFor(id, big = true) else "",
                fmts.distinctBy { it.key }, "YouTube"),
            status = ps?.optString("status").orEmpty().ifEmpty { "ERROR" },
            reason = ps?.optString("reason").orEmpty(),
            expiresInSec = sd?.optString("expiresInSeconds")?.toIntOrNull() ?: 0,
            isLive = vd?.optBoolean("isLive") == true || vd?.optBoolean("isLiveContent") == true && (vd.optString("lengthSeconds") == "0"),
        )
    }
}
