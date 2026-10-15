package com.blaze.assistant.ui

import com.blaze.assistant.ui.GlassPaneGrip.Pane
import com.blaze.assistant.ui.GlassPaneGrip.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.29.9: the glass page window moves only from its rim / title bar, never from the page in the middle. */
class GlassPaneGripTest {
    // A 1000 x 1600 px pane: 54 px rim, title band to y = 190, 132 px corners.
    private val w = 1000f; private val h = 1600f; private val rim = 54f; private val header = 190f; private val corner = 132f
    private fun z(x: Float, y: Float) = GlassPaneGrip.zone(x, y, w, h, rim, header, corner)

    @Test fun centreIsNeverAHandle() {
        assertEquals(Zone.CONTENT, z(500f, 800f))
        assertEquals(Zone.CONTENT, z(rim + 1f, header + 1f))
        assertEquals(Zone.CONTENT, z(w - rim - 1f, 800f))
        // A long scroll down the middle of the page: every point is CONTENT.
        for (y in 200..1500 step 50) assertEquals("y=$y", Zone.CONTENT, z(500f, y.toFloat()))
        assertFalse(GlassPaneGrip.grabs(Zone.CONTENT)); assertFalse(GlassPaneGrip.grabs(Zone.NONE))
    }

    @Test fun rimAndTitleBarMove() {
        assertEquals(Zone.MOVE, z(10f, 800f))          // left rim
        assertEquals(Zone.MOVE, z(w - 10f, 800f))      // right rim
        assertEquals(Zone.MOVE, z(500f, h - 10f))      // bottom rim
        assertEquals(Zone.MOVE, z(500f, 100f))         // title bar
        assertEquals(Zone.MOVE, z(5f, 5f))             // top corners are title bar, not resize
        assertEquals(Zone.MOVE, z(w - 5f, 5f))
        assertTrue(GlassPaneGrip.grabs(Zone.MOVE))
    }

    @Test fun bottomCornersResize() {
        assertEquals(Zone.RESIZE_BOTTOM_RIGHT, z(w - 20f, h - 20f))
        assertEquals(Zone.RESIZE_BOTTOM_LEFT, z(20f, h - 20f))
        assertEquals(Zone.RESIZE_BOTTOM_RIGHT, z(w - 100f, h - 100f))
    }

    @Test fun offThePaneIsNothing() {
        assertEquals(Zone.NONE, z(-1f, 500f)); assertEquals(Zone.NONE, z(500f, h + 1f)); assertEquals(Zone.NONE, z(w + 1f, 10f))
        assertEquals(Zone.NONE, GlassPaneGrip.zone(5f, 5f, 0f, 0f, rim, header, corner))
    }

    private val cw = 1080f; private val ch = 2000f; private val minW = 440f; private val minH = 520f

    @Test fun moveKeepsSizeAndStaysInside() {
        val p = GlassPaneGrip.initial(cw, ch)
        val m = GlassPaneGrip.drag(p, Zone.MOVE, 30f, -40f, cw, ch, minW, minH)
        assertEquals(p.w, m.w, 0.01f); assertEquals(p.h, m.h, 0.01f)
        assertEquals(p.l + 30f, m.l, 0.01f); assertEquals(p.t - 40f, m.t, 0.01f)
        val far = GlassPaneGrip.drag(p, Zone.MOVE, 99_999f, 99_999f, cw, ch, minW, minH)
        assertEquals(cw, far.r, 0.01f); assertEquals(ch, far.b, 0.01f)
        val back = GlassPaneGrip.drag(p, Zone.MOVE, -99_999f, -99_999f, cw, ch, minW, minH)
        assertEquals(0f, back.l, 0.01f); assertEquals(0f, back.t, 0.01f)
    }

    @Test fun contentDragNeverChangesThePane() {
        val p = Pane(100f, 200f, 600f, 900f)
        assertEquals(p, GlassPaneGrip.drag(p, Zone.CONTENT, 300f, 300f, cw, ch, minW, minH))
        assertEquals(p, GlassPaneGrip.drag(p, Zone.NONE, 300f, 300f, cw, ch, minW, minH))
    }

    @Test fun resizeIsSane() {
        val p = Pane(100f, 200f, 600f, 900f)
        val br = GlassPaneGrip.drag(p, Zone.RESIZE_BOTTOM_RIGHT, 50f, 60f, cw, ch, minW, minH)
        assertEquals(Pane(100f, 200f, 650f, 960f), br)
        val bl = GlassPaneGrip.drag(p, Zone.RESIZE_BOTTOM_LEFT, -50f, 60f, cw, ch, minW, minH)
        assertEquals(Pane(50f, 200f, 650f, 960f), bl)
        // Never below the minimum, the top edge never moves, never off the container.
        val tiny = GlassPaneGrip.drag(p, Zone.RESIZE_BOTTOM_RIGHT, -9_999f, -9_999f, cw, ch, minW, minH)
        assertEquals(minW, tiny.w, 0.01f); assertEquals(minH, tiny.h, 0.01f); assertEquals(p.t, tiny.t, 0.01f)
        val tinyL = GlassPaneGrip.drag(p, Zone.RESIZE_BOTTOM_LEFT, 9_999f, 0f, cw, ch, minW, minH)
        assertEquals(minW, tinyL.w, 0.01f); assertEquals(p.r, tinyL.r, 0.01f)
        val huge = GlassPaneGrip.drag(p, Zone.RESIZE_BOTTOM_RIGHT, 9_999f, 9_999f, cw, ch, minW, minH)
        assertEquals(cw, huge.r, 0.01f); assertEquals(ch, huge.b, 0.01f)
    }

    @Test fun fitHandlesASmallerScreen() {
        val p = Pane(500f, 1200f, 900f, 1500f)
        val f = GlassPaneGrip.fit(p, 720f, 1280f, minW, minH)
        assertTrue(f.l >= 0f && f.t >= 0f && f.r <= 720f && f.b <= 1280f)
        val first = GlassPaneGrip.initial(1000f, 1000f)
        assertEquals(940f, first.w, 0.01f); assertEquals(30f, first.l, 0.01f)
    }

    @Test fun windowSourceUsesTheGripAndThePill() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/GlassWindow.kt").readText()
        assertTrue("grab zones decided by GlassPaneGrip", src.contains("GlassPaneGrip.zone(") && src.contains("GlassPaneGrip.grabs(zone)"))
        assertTrue("no drag-anywhere tilt layer", !src.contains("detectDragGestures"))
        assertTrue("page selection uses the chat pill", src.contains("SelectionPill(") && src.contains("PillWebView("))
    }
}
