package com.blaze.assistant.mediaapps.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.1: YouTube search / player parsing and quality picks. Deleted together with mediaapps/youtube/. */
class YtTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource("yt/$n")!!.readText()

    @Test fun searchSkipsLiveAndAdsAndReadsContinuation() {
        val p = YtParse.search(res("search.json"))
        assertEquals(listOf("n61ULEU7CO0", "_jH2oTNRe7g", "zW5wpJY1rgQ"), p.items.map { it.id })
        val first = p.items[0]
        assertEquals("Lofi Girl", first.author)
        assertEquals("6:10:58", first.durationText)
        assertEquals(22258, first.durationSec)
        assertEquals("https://i.ytimg.com/vi/n61ULEU7CO0/mqdefault.jpg", first.thumb)
        assertEquals("youtube", first.source); assertEquals("YouTube", first.licence)
        assertTrue(first.title.isNotBlank())
        assertTrue(p.next.isNotBlank())
    }

    @Test fun continuationPage() {
        val json = """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[
            {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"runs":[{"text":"A "},{"text":"B"}]},
              "longBylineText":{"runs":[{"text":"Chan"}]},"lengthText":{"simpleText":"3:05"}}}]}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}}]}"""
        val p = YtParse.search(json)
        assertEquals(1, p.items.size)
        assertEquals("A B", p.items[0].title)
        assertEquals("Chan", p.items[0].author)
        assertEquals(185, p.items[0].durationSec)
        assertEquals("NEXT", p.next)
    }

    @Test fun playerFormats() {
        val s = YtParse.player(res("player.json"))
        assertEquals("OK", s.status)
        assertEquals("Test song", s.detail.title); assertEquals("Test channel", s.detail.author); assertEquals(215, s.detail.lengthSec)
        assertFalse(s.isLive)
        // signatureCipher-only, DRC and dubbed tracks are dropped; one 140 and one 251 left.
        assertEquals(listOf(18, 137, 299, 136, 247, 244, 140, 251, 249, 250).map { it.toString() }, s.formats.map { it.key })
        val f18 = s.formats.first { it.key == "18" }
        assertTrue(f18.muxed); assertEquals("mp4", f18.container)
        val f140 = s.formats.first { it.key == "140" }
        assertTrue(f140.hasAudio && !f140.hasVideo); assertEquals(129, f140.kbps)
        assertFalse(f140.url.contains("dub"))
    }

    @Test fun playerErrors() {
        val s = YtParse.player("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""")
        assertEquals("LOGIN_REQUIRED", s.status)
        assertTrue(s.formats.isEmpty())
        assertEquals("ERROR", YtParse.player("{}").status)
    }

    @Test fun audioQualityChoices() {
        val s = YtParse.player(res("player.json"))
        val a = YtQuality.audioChoices(s.formats, s.detail.lengthSec)
        assertEquals(listOf("a249", "a250", "a140", "a251"), a.map { it.id })
        assertEquals("Low · Opus 50 kbps", a[0].label)
        assertEquals("Medium · Opus 70 kbps", a[1].label)
        assertEquals("High · AAC 129 kbps", a[2].label)
        assertEquals("opus", a[0].ext); assertEquals("m4a", a[2].ext)
        assertEquals(70_000L * 215 / 8, a[1].bytes)   // no contentLength: bitrate × length
        assertEquals("a140", YtQuality.defaultAudio(a)?.id)
        assertNull(YtQuality.defaultAudio(emptyList()))
    }

    @Test fun videoQualityChoices() {
        val s = YtParse.player(res("player.json"))
        val v = YtQuality.videoChoices(s.formats, s.detail.lengthSec)
        assertEquals(listOf("p18", "v244+251", "v136+140", "v137+140"), v.map { it.id })
        assertEquals(listOf(360, 480, 720, 1080), v.map { it.height })
        assertEquals("webm", v[1].ext); assertEquals("mp4", v[2].ext)
        assertEquals(listOf("137", "140"), v[3].keys)   // 30 fps preferred over 60
        assertEquals(30_000_000L + 3_500_000L, v[2].bytes)
        assertEquals("v136+140", YtQuality.defaultVideo(v)?.id)
        assertEquals(listOf("136", "140"), YtQuality.streamPick(s.formats).map { it.key })
        assertEquals(listOf("18"), YtQuality.streamPick(s.formats, 360).map { it.key })
        assertNotNull(YtQuality.audioFor(s.formats, "webm"))
    }
}
