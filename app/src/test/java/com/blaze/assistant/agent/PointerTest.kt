package com.blaze.assistant.agent

import com.blaze.assistant.phone.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.21.0 visible pointer: timing, re-aim, double-tap and own-touch rules. */
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
}
