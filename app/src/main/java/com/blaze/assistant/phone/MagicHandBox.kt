package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Box

/**
 * Magic Hand grab box (v0.26.0), pure part (MagicHandBoxTest): after a circle-to-grab, the blue rectangle can be
 * moved (drag from inside) and resized (drag a corner or an edge) to fix a wrong selection. The text is read again
 * for the new rectangle when the finger lifts. All in screen px.
 */
object MagicHandBox {
    /** What a touch on the box grabs. MOVE = inside; the rest resize the named sides. */
    enum class Handle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
        NONE, MOVE,
        LEFT(left = true), TOP(top = true), RIGHT(right = true), BOTTOM(bottom = true),
        TOP_LEFT(left = true, top = true), TOP_RIGHT(right = true, top = true),
        BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true);
        val resizes: Boolean get() = left || top || right || bottom
    }

    /** Where the box may go: the screen minus the status bar and the navigation bar. */
    data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    fun bounds(screenW: Int, screenH: Int, topInset: Int = 0, bottomInset: Int = 0): Bounds =
        Bounds(0, topInset.coerceAtLeast(0), screenW.coerceAtLeast(1), (screenH - bottomInset).coerceAtLeast(topInset + 1))

    /**
     * Which handle a touch at ([x], [y]) is on. Corners win over edges, edges over the inside; [reach] is how far
     * from a corner or an edge (inside or outside the box) still counts. On a box too small to tell the inside from
     * its edges, the inside (MOVE) wins in the middle third so a small box can still be dragged.
     */
    fun hit(box: Box, x: Int, y: Int, reach: Int): Handle {
        if (x < box.l - reach || x > box.r + reach || y < box.t - reach || y > box.b + reach) return Handle.NONE
        val nearL = kotlin.math.abs(x - box.l) <= reach
        val nearR = kotlin.math.abs(x - box.r) <= reach
        val nearT = kotlin.math.abs(y - box.t) <= reach
        val nearB = kotlin.math.abs(y - box.b) <= reach
        val inside = x in box.l..box.r && y in box.t..box.b
        // Small box: the middle third is always "move".
        if (inside && (box.w < reach * 3 || box.h < reach * 3)) {
            val mx = x - box.l in box.w / 3..box.w * 2 / 3
            val my = y - box.t in box.h / 3..box.h * 2 / 3
            if (mx && my) return Handle.MOVE
        }
        // Prefer the closer side when the box is so thin both are in reach.
        val l = nearL && (!nearR || x - box.l <= box.r - x)
        val r = nearR && !l
        val t = nearT && (!nearB || y - box.t <= box.b - y)
        val b = nearB && !t
        return when {
            l && t -> Handle.TOP_LEFT
            r && t -> Handle.TOP_RIGHT
            l && b -> Handle.BOTTOM_LEFT
            r && b -> Handle.BOTTOM_RIGHT
            l && y in box.t..box.b -> Handle.LEFT
            r && y in box.t..box.b -> Handle.RIGHT
            t && x in box.l..box.r -> Handle.TOP
            b && x in box.l..box.r -> Handle.BOTTOM
            inside -> Handle.MOVE
            else -> Handle.NONE
        }
    }

    /**
     * The box after dragging [handle] by ([dx], [dy]) from [start]: MOVE keeps the size and stays inside [bounds];
     * a resize moves only the named sides, never past [bounds] and never smaller than [minSize] on either side
     * (the opposite side stays put).
     */
    fun drag(start: Box, handle: Handle, dx: Int, dy: Int, bounds: Bounds, minSize: Int): Box {
        val min = minSize.coerceAtMost(minOf(bounds.w, bounds.h)).coerceAtLeast(1)
        val s = fit(start, bounds, min)
        return when {
            handle == Handle.NONE -> s
            handle == Handle.MOVE -> {
                val l = (s.l + dx).coerceIn(bounds.l, bounds.r - s.w)
                val t = (s.t + dy).coerceIn(bounds.t, bounds.b - s.h)
                Box(l, t, l + s.w, t + s.h)
            }
            else -> {
                val l = if (handle.left) (s.l + dx).coerceIn(bounds.l, s.r - min) else s.l
                val r = if (handle.right) (s.r + dx).coerceIn(s.l + min, bounds.r) else s.r
                val t = if (handle.top) (s.t + dy).coerceIn(bounds.t, s.b - min) else s.t
                val b = if (handle.bottom) (s.b + dy).coerceIn(s.t + min, bounds.b) else s.b
                Box(l, t, r, b)
            }
        }
    }

    /** [b] made valid: at least [minSize] each way, inside [bounds] (shifted in, not cut, when it can be). */
    fun fit(b: Box, bounds: Bounds, minSize: Int): Box {
        val min = minSize.coerceAtMost(minOf(bounds.w, bounds.h)).coerceAtLeast(1)
        val w = (b.w).coerceIn(min, bounds.w)
        val h = (b.h).coerceIn(min, bounds.h)
        val l = b.l.coerceIn(bounds.l, bounds.r - w)
        val t = b.t.coerceIn(bounds.t, bounds.b - h)
        return Box(l, t, l + w, t + h)
    }

    /** True when the box moved or changed size enough (over [slop] px on any side) to read the text again. */
    fun changed(a: Box, b: Box, slop: Int): Boolean =
        kotlin.math.abs(a.l - b.l) > slop || kotlin.math.abs(a.t - b.t) > slop || kotlin.math.abs(a.r - b.r) > slop || kotlin.math.abs(a.b - b.b) > slop

    /** The touch window around the box: the box plus [pad] on every side for the handles (it may poke off-screen). */
    fun window(b: Box, pad: Int): Box = Box(b.l - pad, b.t - pad, b.r + pad, b.b + pad)

    /** How long after the finger lifts the text is read again (a quick second drag cancels the first read). */
    const val REREAD_DELAY_MS = 350L
    /** The smallest box, in dp. */
    const val MIN_DP = 40
    /** How far from a corner or edge still grabs it, in dp. */
    const val REACH_DP = 22
    /** The touch margin around the box, in dp. */
    const val PAD_DP = 26
}
