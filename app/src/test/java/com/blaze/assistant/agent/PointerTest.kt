package com.blaze.assistant.agent

import com.blaze.assistant.phone.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Visible pointer: timing, re-aim, double-tap and own-touch rules (v0.21.0); always on, spark blob, click check (v0.24.4). */
class PointerTest {
    @Test fun glideIsQuickButVisible() {
        assertEquals(120L, Pointer.glideMs(0f, 0f, 2f))
        assertTrue(Pointer.glideMs(200f, 0f, 2f) in 121..419)
        assertEquals(420L, Pointer.glideMs(4000f, 3000f, 2f))
        assertTrue(Pointer.rippleMs(true) > Pointer.rippleMs(false))
    }

    @Test fun targetThatMovedIsAimedAgain() {
        assertFalse(Pointer.moved(100f, 100f, 120f, 110f, 2f))   // ~11 dp
        assertTrue(Pointer.moved(100f, 100f, 100f, 200f, 2f))    // 50 dp
    }

    @Test fun doubleTapOnlyWhenQuickAndStill() {
        assertTrue(Pointer.isDoubleTap(1000, 1200, 4f, 2f))
        assertFalse(Pointer.isDoubleTap(0, 1200, 0f, 2f))         // first tap
        assertFalse(Pointer.isDoubleTap(1000, 1600, 0f, 2f))      // too slow
        assertFalse(Pointer.isDoubleTap(1000, 1200, 80f, 2f))     // that was a drag: never a click
    }

    @Test fun blazesOwnTouchesDontPause() {
        assertTrue(Pointer.isOwnTouch(1000, 1200))
        assertFalse(Pointer.isOwnTouch(1300, 1200))
    }

    @Test fun autoByDefault() {
        assertEquals(Pointer.Mode.AUTO, Pointer.mode)
    }

    @Test fun alwaysOnAndEasyToGrab() {
        assertTrue(Pointer.enabled)                       // agent taps always show it; show_pointer is ignored
        assertTrue(Pointer.TOUCH_DP >= 56)
        assertTrue(Pointer.MASCOT_DP in 48..56)
        // The mascot fits in the window below its tip, and the tip (click point) sits near the top.
        assertTrue(Pointer.TIP_Y_DP + Pointer.MASCOT_DP <= Pointer.TOUCH_DP)
        assertTrue(Pointer.TIP_X_DP in 30f..40f)
    }

    @Test fun mascotIsLukesFlame() {
        val M = com.blaze.assistant.phone.Mascot
        val b = M.bounds(M.body)
        assertEquals(-1f, b[0], 0.01f); assertEquals(1f, b[2], 0.01f); assertEquals(1f, b[3], 0.01f)   // round body, radius 1
        assertTrue(b[1] < -1.5f)                                                                        // the flame rises above it
        assertTrue(M.TIP[0] > 0f && M.TIP[1] < -1.5f)                                                   // tip at the top right
        assertEquals(M.HEIGHT, 1f - M.TIP[1], 0.01f)
        assertEquals(2, M.EYES.size); assertTrue(M.EYES.all { it[1] < 0f && it[1] > -0.4f })
        assertEquals(M.STOPS.size, M.COLORS.size)
        assertTrue((M.COLORS.first() shr 16 and 0xFF) > 0xF0)          // orange (red-heavy) at the top
        assertTrue((M.COLORS.last() and 0xFF) > 0xF0)                  // blue at the bottom
        // Walking it anchored at the tip puts the tip exactly on the anchor, and squish keeps it there.
        val p = M.at(M.TIP, 100f, 50f, 20f, 1.2f, 0.8f); assertEquals(100f, p[0], 0.001f); assertEquals(50f, p[1], 0.001f)
        var maxY = -1e9f
        M.walk(M.body, 100f, 50f, 20f, move = { _, y -> maxY = maxOf(maxY, y) }, cubic = { _, _, _, _, _, y -> maxY = maxOf(maxY, y) })
        assertEquals(50f + M.HEIGHT * 20f, maxY, 0.6f)
    }

    @Test fun pulseAndSquishStayLight() {
        assertEquals(1f, Pointer.pulseScale(0), 0.001f)
        assertTrue(Pointer.pulseScale(300) in 1.05f..1.08f)
        val (sx0, sy0) = Pointer.squish(-1f); assertEquals(1f, sx0, 0f); assertEquals(1f, sy0, 0f)
        val (sx, sy) = Pointer.squish(0.5f); assertTrue(sx > 1.1f && sy < 0.9f)
        val (sx1, sy1) = Pointer.squish(1f); assertEquals(1f, sx1, 0.01f); assertEquals(1f, sy1, 0.01f)
    }

    @Test fun manualClickIsCheckedFirst() {
        assertEquals(Pointer.ClickCheck.SHOW_THEN_CLICK, Pointer.checkClick("Add to cart"))
        assertEquals(Pointer.ClickCheck.SHOW_THEN_CLICK, Pointer.checkClick("Settings"))
        for (risky in listOf("Pay now", "Send", "Delete chat", "Confirm transfer", "Buy K50 bundle", "Place order"))
            assertEquals(risky, Pointer.ClickCheck.ASK, Pointer.checkClick(risky))
        assertEquals(Pointer.ClickCheck.ASK, Pointer.checkClick(null))
        assertEquals(Pointer.ClickCheck.ASK, Pointer.checkClick("  "))
        assertEquals(Pointer.ClickCheck.ASK, Pointer.checkClick("Open", candidates = 2))
        assertFalse(Pointer.risky("Sender name"))           // whole words only
        assertEquals("Click \u2018Add to cart\u2019?", Pointer.clickLine("Add to cart"))
        assertEquals("Click here?", Pointer.clickLine(null))
        val long = Pointer.clickLine("A very long label that goes on and on and on forever")
        assertTrue(long.length <= 42 && long.contains("\u2026"))
        assertEquals(1_500L, Pointer.CONFIRM_MS)
    }

    @Test fun workBannerSwipes() {
        val t = 100f
        assertEquals(com.blaze.assistant.ui.BannerSwipe.MINIMISE, com.blaze.assistant.ui.bannerSwipe(0f, -150f, t))   // up
        assertEquals(com.blaze.assistant.ui.BannerSwipe.MINIMISE, com.blaze.assistant.ui.bannerSwipe(-200f, 10f, t))  // left
        assertEquals(com.blaze.assistant.ui.BannerSwipe.MINIMISE, com.blaze.assistant.ui.bannerSwipe(220f, 20f, t))   // right
        assertEquals(com.blaze.assistant.ui.BannerSwipe.OPEN, com.blaze.assistant.ui.bannerSwipe(10f, 150f, t))       // down opens Blaze
        assertEquals(com.blaze.assistant.ui.BannerSwipe.NONE, com.blaze.assistant.ui.bannerSwipe(30f, 40f, t))        // a nudge springs back
        assertEquals(com.blaze.assistant.ui.BannerSwipe.NONE, com.blaze.assistant.ui.bannerSwipe(0f, 0f, t))
    }
}
