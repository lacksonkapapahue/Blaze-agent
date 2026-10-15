package com.blaze.assistant.ui

/**
 * v0.29.9: where the floating glass window (the page pane on Home) can be grabbed. Pure Kotlin (GlassPaneGripTest).
 *
 * The pane only moves when you hold its glass rim or its title bar. The page in the middle is never a handle: it is
 * left alone for scrolling, selecting and copying text. The two bottom corners resize it. All in px, pane-local
 * coordinates (0,0 = the pane's top-left corner).
 */
object GlassPaneGrip {
    enum class Zone { NONE, CONTENT, MOVE, RESIZE_BOTTOM_LEFT, RESIZE_BOTTOM_RIGHT }

    /** The glass rim around the page, in dp (the drag zone on the left, right and bottom sides). */
    const val RIM_DP = 18
    /** How far from a bottom corner still grabs it for resizing, in dp (a 44dp target with the rim). */
    const val CORNER_DP = 44
    /** The smallest pane, in dp. */
    const val MIN_W_DP = 220
    const val MIN_H_DP = 260

    /**
     * What a touch at ([x], [y]) on a [w] x [h] pane grabs. [header] is the height of the top band (the rim above the
     * title plus the title bar itself). Bottom corners win over the rim; anything inside the rim and below the header is
     * CONTENT (the page). A touch off the pane is NONE.
     */
    fun zone(x: Float, y: Float, w: Float, h: Float, rim: Float, header: Float, corner: Float): Zone {
        if (w <= 0f || h <= 0f || x < 0f || y < 0f || x > w || y > h) return Zone.NONE
        val c = corner.coerceAtMost(minOf(w, h) / 2f)
        if (y >= h - c && y > header) {
            if (x <= c) return Zone.RESIZE_BOTTOM_LEFT
            if (x >= w - c) return Zone.RESIZE_BOTTOM_RIGHT
        }
        if (y <= header) return Zone.MOVE
        if (x <= rim || x >= w - rim || y >= h - rim) return Zone.MOVE
        return Zone.CONTENT
    }

    /** The pane's place and size in its container (px). */
    data class Pane(val l: Float, val t: Float, val w: Float, val h: Float) {
        val r: Float get() = l + w
        val b: Float get() = t + h
    }

    /** The first place: [widthFrac] x [heightFrac] of the container, centred. */
    fun initial(cw: Float, ch: Float, widthFrac: Float = 0.94f, heightFrac: Float = 0.78f): Pane {
        val w = cw * widthFrac; val h = ch * heightFrac
        return Pane((cw - w) / 2f, (ch - h) / 2f, w, h)
    }

    /** [p] made valid in a [cw] x [ch] container: no smaller than the minimum, no bigger than the container, fully inside. */
    fun fit(p: Pane, cw: Float, ch: Float, minW: Float, minH: Float): Pane {
        if (cw <= 0f || ch <= 0f) return p
        val w = p.w.coerceIn(minOf(minW, cw), cw)
        val h = p.h.coerceIn(minOf(minH, ch), ch)
        val l = p.l.coerceIn(0f, cw - w)
        val t = p.t.coerceIn(0f, ch - h)
        return Pane(l, t, w, h)
    }

    /**
     * The pane after dragging [zone] by ([dx], [dy]) from [start]. MOVE keeps the size and stays inside the container.
     * A bottom-corner resize moves only that corner's sides (the top edge never moves, so the title bar stays under the
     * finger's start), never below the minimum and never off the container. CONTENT and NONE never change it.
     */
    fun drag(start: Pane, zone: Zone, dx: Float, dy: Float, cw: Float, ch: Float, minW: Float, minH: Float): Pane {
        val s = fit(start, cw, ch, minW, minH)
        val mw = minOf(minW, cw); val mh = minOf(minH, ch)
        return when (zone) {
            Zone.NONE, Zone.CONTENT -> s
            Zone.MOVE -> Pane((s.l + dx).coerceIn(0f, cw - s.w), (s.t + dy).coerceIn(0f, ch - s.h), s.w, s.h)
            Zone.RESIZE_BOTTOM_RIGHT -> {
                val r = (s.r + dx).coerceIn(s.l + mw, cw)
                val b = (s.b + dy).coerceIn(s.t + mh, ch)
                Pane(s.l, s.t, r - s.l, b - s.t)
            }
            Zone.RESIZE_BOTTOM_LEFT -> {
                val l = (s.l + dx).coerceIn(0f, s.r - mw)
                val b = (s.b + dy).coerceIn(s.t + mh, ch)
                Pane(l, s.t, s.r - l, b - s.t)
            }
        }
    }

    /** True for the zones that drag the pane (the page in the middle never does). */
    fun grabs(zone: Zone): Boolean = zone == Zone.MOVE || zone == Zone.RESIZE_BOTTOM_LEFT || zone == Zone.RESIZE_BOTTOM_RIGHT
}
