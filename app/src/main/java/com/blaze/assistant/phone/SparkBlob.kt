package com.blaze.assistant.phone

/**
 * Blaze's mascot, the spark blob: the same outlines as the app icon (ic_launcher_foreground, 108-unit viewport,
 * blob ~54 units wide centred on 54,54, a four-point spark in the middle). Pure geometry so the on-screen pointer
 * (phone/Pointer.kt, android.graphics.Path) and its Compose preview (ui/PointerPreview.kt) draw the exact same shape.
 *
 * Each shape = a start point + cubic segments (c1x, c1y, c2x, c2y, x, y), closed.
 */
object SparkBlob {
    class Shape(val start: FloatArray, val cubics: List<FloatArray>)

    /** Icon units across the blob: a pointer of N px is drawn at N / SPAN px per unit. */
    const val SPAN = 54f
    private const val C = 54f

    val blob = Shape(floatArrayOf(54f, 28f), listOf(
        floatArrayOf(68f, 27.5f, 80.5f, 37f, 81f, 50.5f),
        floatArrayOf(81.5f, 64.5f, 72.5f, 79.5f, 56.5f, 80f),
        floatArrayOf(41.5f, 80.5f, 27.5f, 72.5f, 27f, 57.5f),
        floatArrayOf(26.5f, 42.5f, 38.5f, 28.5f, 54f, 28f)))

    val spark = Shape(floatArrayOf(54f, 40f), listOf(
        floatArrayOf(55.6f, 49.6f, 58.4f, 52.4f, 68f, 54f),
        floatArrayOf(58.4f, 55.6f, 55.6f, 58.4f, 54f, 68f),
        floatArrayOf(52.4f, 58.4f, 49.6f, 55.6f, 40f, 54f),
        floatArrayOf(49.6f, 52.4f, 52.4f, 49.6f, 54f, 40f)))

    /** Brand colours: ink blob, paper outline, accent spark (orange while the user has the pointer). */
    const val INK = 0xFF111110.toInt()
    const val PAPER = 0xFFFCFCFC.toInt()
    const val SPARK = 0xFF6B8CFF.toInt()
    const val SPARK_PAUSED = 0xFFE8743B.toInt()

    /**
     * Walks [s] scaled to [sizePx] across (with [sx]/[sy] squish) and centred on (cx, cy): [move] once, then [cubic]
     * per segment. Callers close the path.
     */
    fun walk(s: Shape, cx: Float, cy: Float, sizePx: Float, sx: Float = 1f, sy: Float = 1f,
                    move: (Float, Float) -> Unit, cubic: (Float, Float, Float, Float, Float, Float) -> Unit) {
        val k = sizePx / SPAN
        val X = { v: Float -> cx + (v - C) * k * sx }
        val Y = { v: Float -> cy + (v - C) * k * sy }
        move(X(s.start[0]), Y(s.start[1]))
        for (c in s.cubics) cubic(X(c[0]), Y(c[1]), X(c[2]), Y(c[3]), X(c[4]), Y(c[5]))
    }

    /** Bounds of a shape in icon units (minX, minY, maxX, maxY), from its end and control points (tests). */
    fun bounds(s: Shape): FloatArray {
        val xs = listOf(s.start[0]) + s.cubics.flatMap { listOf(it[0], it[2], it[4]) }
        val ys = listOf(s.start[1]) + s.cubics.flatMap { listOf(it[1], it[3], it[5]) }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}
