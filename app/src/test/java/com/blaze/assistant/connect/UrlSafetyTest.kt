package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {
    @Test fun httpsIsNeverRisky() {
        assertFalse(UrlSafety.isRiskyCleartext("https://api.example.com/v1"))
        assertFalse(UrlSafety.isRiskyCleartext(""))
    }

    @Test fun localHttpIsAllowed() {
        listOf(
            "http://127.0.0.1:8080/v1", "http://localhost:11434", "http://192.168.1.20:20128/v1",
            "http://10.0.0.5/mcp", "http://172.20.1.1/sse", "http://100.101.102.103:7377",
            "http://my-pc.local:3000", "http://box.tail1234.ts.net/mcp", "http://[::1]:8080", "HTTP://LOCALHOST/",
        ).forEach { assertFalse(it, UrlSafety.isRiskyCleartext(it)) }
    }

    @Test fun publicHttpIsRisky() {
        listOf(
            "http://api.example.com/v1", "http://8.8.8.8/mcp", "http://172.32.0.1/", "http://100.128.0.1/",
            "http://user:pw@example.org:80/x", "http://192.169.0.1",
        ).forEach { assertTrue(it, UrlSafety.isRiskyCleartext(it)) }
    }

    @Test fun hostParsing() {
        assertEquals("example.org", UrlSafety.host("http://user:pw@Example.org:80/x?y#z"))
        assertEquals("::1", UrlSafety.host("http://[::1]:8080/"))
    }
}
