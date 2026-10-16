package com.blaze.assistant.media

import com.blaze.assistant.media.QwenImageApi.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.8: Qwen image generation through chat.qwen.ai, the pure parts (request bodies, SSE parsing, tokens, phrase). */
class QwenImageApiTest {

    // ---------------------------------------------------------------- requests

    @Test fun ratioPicksNearestOffered() {
        assertEquals("1:1", QwenImageApi.ratioFor(1024, 1024))
        assertEquals("9:16", QwenImageApi.ratioFor(1080, 1920))
        assertEquals("16:9", QwenImageApi.ratioFor(1920, 1080))
        assertEquals("4:3", QwenImageApi.ratioFor(1536, 1024))
        assertEquals("3:4", QwenImageApi.ratioFor(768, 1024))
        assertEquals("1:1", QwenImageApi.ratioFor(0, 500))
    }

    @Test fun newChatBodyIsT2i() {
        val b = QwenImageApi.newChatBody("qwen3.7-plus", 1_700_000_000_000L)
        assertEquals("t2i", b.getString("chat_type"))
        assertEquals("normal", b.getString("chat_mode"))
        assertEquals("qwen3.7-plus", b.getJSONArray("models").getString(0))
        assertEquals(1_700_000_000_000L, b.getLong("timestamp"))
        assertEquals("", b.getString("project_id"))
    }

    @Test fun completionBodyCarriesPromptSizeAndChatType() {
        val b = QwenImageApi.completionBody("chat-1", "qwen3.7-plus", "a red fox in snow", "9:16", 42L, "fid-1")
        assertTrue(b.getBoolean("stream"))
        assertTrue(b.getBoolean("incremental_output"))
        assertEquals("2.1", b.getString("version"))
        assertEquals("chat-1", b.getString("chat_id"))
        assertEquals("9:16", b.getString("size"))
        val m = b.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", m.getString("role"))
        assertEquals("a red fox in snow", m.getString("content"))
        assertEquals("t2i", m.getString("chat_type"))
        assertEquals("t2i", m.getString("sub_chat_type"))
        assertEquals("t2i", m.getJSONObject("extra").getJSONObject("meta").getString("subChatType"))
        assertEquals("phase", m.getJSONObject("feature_config").getString("output_schema"))
        assertEquals("fid-1", m.getString("fid"))
        assertTrue(m.isNull("parentId"))
        assertEquals(0, m.getJSONArray("files").length())
    }

    @Test fun completionUrlEncodesChatId() {
        assertEquals("https://chat.qwen.ai/api/v2/chat/completions?chat_id=abc-123", QwenImageApi.completionUrl("abc-123"))
    }

    @Test fun headersCarryTokenAndCookie() {
        val h = QwenImageApi.headers("TOKEN", "", "UA", "https://chat.qwen.ai/c/x", "rid")
        assertEquals("Bearer TOKEN", h["Authorization"])
        assertEquals("token=TOKEN", h["Cookie"])
        assertEquals("web", h["source"])
        assertEquals("https://chat.qwen.ai", h["Origin"])
        // The browser's cookie line is kept whole (WAF cookies ride along); token added when it's missing.
        assertEquals("token=T; ssxmod_itna=1", QwenImageApi.headers("T", "token=T; ssxmod_itna=1", "UA", "r", "id")["Cookie"])
        assertEquals("token=T; acw_tc=9", QwenImageApi.headers("T", "acw_tc=9", "UA", "r", "id")["Cookie"])
    }

    // ---------------------------------------------------------------- responses

    @Test fun picksFirstT2iModel() {
        val models = """{"data":[{"id":"qwen-text","info":{"meta":{"chat_type":["t2t"]}}},
            {"id":"qwen3.7-plus","info":{"meta":{"chat_type":["t2t","t2v","t2i","image_edit"]}}},
            {"id":"qwen3.8-max","info":{"meta":{"chat_type":["t2i"]}}}]}"""
        assertEquals("qwen3.7-plus", QwenImageApi.pickModel(models))
        assertNull(QwenImageApi.pickModel("""{"data":[{"id":"x","info":{"meta":{"chat_type":["t2t"]}}}]}"""))
        assertNull(QwenImageApi.pickModel("<html>"))
    }

    @Test fun chatIdFromNewChat() {
        assertEquals("c-9", QwenImageApi.chatIdOf(200, "application/json", """{"success":true,"request_id":"r","data":{"id":"c-9"}}""").getOrThrow())
        val auth = QwenImageApi.chatIdOf(401, "application/json", """{"detail":"Unauthorized"}""").exceptionOrNull() as QwenImageApi.QwenFailure
        assertEquals(Kind.AUTH, auth.kind)
        val notLogged = QwenImageApi.chatIdOf(200, "application/json", """{"success":false,"data":{"code":"Unauthorized","details":"not login"}}""").exceptionOrNull() as QwenImageApi.QwenFailure
        assertEquals(Kind.AUTH, notLogged.kind)
        val noId = QwenImageApi.chatIdOf(200, "application/json", """{"success":true,"data":{}}""").exceptionOrNull() as QwenImageApi.QwenFailure
        assertEquals(Kind.FORMAT, noId.kind)
    }

    @Test fun wafPageIsItsOwnFailure() {
        val html = "<!doctype html>\n<meta charset=\"UTF-8\">\n<meta name=\"aliyun_waf_aa\" content=\"ff\">\n<script>/* v2.0.1 */</script>"
        val f = QwenImageApi.chatIdOf(200, "text/html; charset=utf-8", html).exceptionOrNull() as QwenImageApi.QwenFailure
        assertEquals(Kind.WAF, f.kind)
        assertEquals(Kind.WAF, (QwenImageApi.parseStream(405, "text/html", html).exceptionOrNull() as QwenImageApi.QwenFailure).kind)
    }

    @Test fun rateLimitAndHttpErrors() {
        val rl = QwenImageApi.parseStream(200, "application/json", """{"success":false,"request_id":"r","data":{"code":"RateLimited","details":"limit","num":3}}""")
        val f = rl.exceptionOrNull() as QwenImageApi.QwenFailure
        assertEquals(Kind.RATE_LIMIT, f.kind)
        assertTrue(f.message!!, f.message!!.contains("3 h"))
        assertEquals(Kind.RATE_LIMIT, (QwenImageApi.parseStream(429, "application/json", "{}").exceptionOrNull() as QwenImageApi.QwenFailure).kind)
        assertEquals(Kind.HTTP, (QwenImageApi.parseStream(500, "application/json", "oops").exceptionOrNull() as QwenImageApi.QwenFailure).kind)
    }

    private fun ev(o: String) = "data: $o\n\n"

    @Test fun parsesImageUrlFromPhaseStream() {
        val sse = ev("""{"response.created":{"chat_id":"c","response_id":"r1"}}""") +
            ev("""{"choices":[{"delta":{"role":"assistant","content":"","phase":"image_gen","status":"typing"}}]}""") +
            ev("""{"choices":[{"delta":{"role":"assistant","content":"https://cdn.qwenlm.ai/output/u/t2i/abc/1.png?key=xyz","phase":"image_gen","status":"typing","extra":{}}}],"response_id":"r1"}""") +
            ev("""{"choices":[{"delta":{"content":"","phase":"image_gen","status":"finished"}}]}""") + "data: [DONE]\n\n"
        assertEquals("https://cdn.qwenlm.ai/output/u/t2i/abc/1.png?key=xyz", QwenImageApi.parseStream(200, "text/event-stream", sse).getOrThrow())
    }

    @Test fun parsesMarkdownImageAndImageList() {
        assertEquals("https://cdn.example.com/a.png",
            QwenImageApi.parseEvent("""{"choices":[{"delta":{"phase":"image_gen","content":"![image](https://cdn.example.com/a.png)"}}]}""").first)
        assertEquals("https://wanx.alicdn.com/b.jpg",
            QwenImageApi.parseEvent("""{"choices":[{"delta":{"phase":"answer","content":"","extra":{"image_list":[{"image":"https://wanx.alicdn.com/b.jpg"}]}}}]}""").first)
        // Plain answer text with a link but no picture is not an image.
        assertNull(QwenImageApi.parseEvent("""{"choices":[{"delta":{"phase":"answer","content":"see https://qwen.ai"}}]}""").first)
        assertNull(QwenImageApi.parseEvent("""{"choices":[{"delta":{"phase":"think","content":"https://x.y/z.png"}}]}""").first)
        assertNull(QwenImageApi.parseEvent("not json").first)
    }

    @Test fun errorInsideStream() {
        val sse = ev("""{"error":{"code":"RateLimited","details":"You have reached the limit"}}""")
        val f = QwenImageApi.parseStream(200, "text/event-stream", sse).exceptionOrNull() as QwenImageApi.QwenFailure
        assertEquals(Kind.RATE_LIMIT, f.kind)
        val none = QwenImageApi.parseStream(200, "text/event-stream", ev("""{"choices":[{"delta":{"phase":"answer","content":"I can't draw that."}}]}"""))
        assertEquals(Kind.FORMAT, (none.exceptionOrNull() as QwenImageApi.QwenFailure).kind)
    }

    @Test fun ssePayloadsHandleCrlfAndMultiline() {
        val p = QwenImageApi.ssePayloads("data: {\"a\":1}\r\n\r\n: keep-alive\n\ndata: {\"b\":\ndata: 2}\n\ndata: [DONE]\n\n")
        assertEquals(listOf("{\"a\":1}", "{\"b\":\n2}"), p)
        assertEquals(2, JSONObject(p[1]).getInt("b"))
    }

    // ---------------------------------------------------------------- token

    private val jwt = run {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." + enc.encodeToString("""{"id":"u1","exp":2000000000}""".toByteArray()) + ".sig"
    }

    @Test fun tokenFromCookieAndLocalStorage() {
        assertEquals(jwt, QwenImageApi.tokenFromCookies("ssxmod_itna=abc; token=$jwt; acw_tc=1"))
        assertNull(QwenImageApi.tokenFromCookies("ssxmod_itna=abc; atoken=zzzzzzzzzzzzzzzzzzzzzzzz"))
        assertNull(QwenImageApi.tokenFromCookies(null))
        assertEquals(jwt, QwenImageApi.tokenFromJs("\"$jwt\""))
        assertNull(QwenImageApi.tokenFromJs("null"))
        assertNull(QwenImageApi.tokenFromJs("\"\""))
    }

    @Test fun jwtExpiry() {
        assertEquals(2_000_000_000L, QwenImageApi.jwtExp(jwt))
        assertFalse(QwenImageApi.expired(jwt, 1_900_000_000_000L))
        assertTrue(QwenImageApi.expired(jwt, 2_000_000_000_000L))
        assertFalse(QwenImageApi.expired("not-a-jwt-but-long-enough-token", 2_000_000_000_000L))
    }

    // ---------------------------------------------------------------- phrase

    @Test fun routePhrase() {
        assertEquals("pollinations", QwenImageApi.routePhrase("use pollinations for images"))
        assertEquals("pollinations", QwenImageApi.routePhrase("Use Pollinations for images."))
        assertEquals("pollinations", QwenImageApi.routePhrase("switch back to pollinations for pictures"))
        assertEquals("qwen", QwenImageApi.routePhrase("use qwen for images"))
        assertEquals("qwen", QwenImageApi.routePhrase("switch to Qwen for images again"))
        assertEquals("qwen", QwenImageApi.routePhrase("make images with qwen"))
        // The chat model, not pictures.
        assertNull(QwenImageApi.routePhrase("use qwen"))
        assertNull(QwenImageApi.routePhrase("use pollinations"))
        assertNull(QwenImageApi.routePhrase("draw a cat using pollinations style"))
        assertNull(QwenImageApi.routePhrase("what is pollinations?"))
    }
}
