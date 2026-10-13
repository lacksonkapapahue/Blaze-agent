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
        assertTrue(Pointer.BLOB_DP in 48..56)
        // The blob fits in the window, and the tip marker sits up-left of it, outside the blob.
        assertTrue(Pointer.BLOB_CENTER_DP + Pointer.BLOB_DP / 2f <= Pointer.TOUCH_DP)
        assertTrue(Pointer.TIP_DP < Pointer.BLOB_CENTER_DP - Pointer.BLOB_DP / 2f + 4)
    }

    @Test fun sparkBlobIsTheIconArt() {
        val b = com.blaze.assistant.phone.SparkBlob.bounds(com.blaze.assistant.phone.SparkBlob.blob)
        assertTrue(b[2] - b[0] in 50f..58f); assertTrue(b[3] - b[1] in 50f..58f)
        val sp = com.blaze.assistant.phone.SparkBlob.bounds(com.blaze.assistant.phone.SparkBlob.spark)
        assertEquals(28f, sp[2] - sp[0], 0.01f)
        // Walking it at 48 px centred on (100, 100) stays within ~half its size of the centre.
        var minX = 1e9f; var maxX = -1e9f
        com.blaze.assistant.phone.SparkBlob.walk(com.blaze.assistant.phone.SparkBlob.blob, 100f, 100f, 48f, move = { x, _ -> minX = minOf(minX, x); maxX = maxOf(maxX, x) },
            cubic = { _, _, _, _, x, _ -> minX = minOf(minX, x); maxX = maxOf(maxX, x) })
        assertTrue(minX > 70f && maxX < 130f && maxX - minX > 40f)
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
}
