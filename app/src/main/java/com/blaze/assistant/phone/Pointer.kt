package com.blaze.assistant.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Blaze's visible pointer for phone control (v0.21.0).
 *
 * Before Blaze touches the screen it glides a pointer to the target, re-checks that the target is
 * still there (the orient step), shows a short ripple, then acts, so you see it coming. Touch the
 * screen or the pointer and Blaze pauses at once (manual mode): drag the pointer anywhere (dragging
 * never clicks), double-tap it to have Blaze click right there, then Blaze looks and carries on.
 * A small bar offers Resume and Stop while paused.
 *
 * Light on purpose (Galaxy A12): plain Views in accessibility overlay windows, no Compose, and it only
 * animates while gliding or rippling; idle in auto mode it hides after a few seconds.
 */
object Pointer {
    enum class Mode { AUTO, PAUSED }

    /** What the user did while Blaze was paused. */
    sealed class Event {
        object Resume : Event()
        object Stop : Event()
        data class Click(val x: Float, val y: Float) : Event()
    }

    const val ACCENT = 0xFF1F4BD8.toInt()

    @Volatile var mode = Mode.AUTO
        private set
    val enabled: Boolean get() = com.blaze.assistant.agent.Settings.bool("show_pointer", true)

    // ---------- pure rules (PointerTest) ----------
    /** Glide time: quick for short hops, never slow (120–420 ms), by distance in dp. */
    fun glideMs(dxPx: Float, dyPx: Float, density: Float): Long {
        val dp = kotlin.math.sqrt(dxPx * dxPx + dyPx * dyPx) / density.coerceAtLeast(0.5f)
        return (120 + dp * 0.6f).toLong().coerceIn(120, 420)
    }
    /** Ripple before acting: short for a tap, held for a long press. */
    fun rippleMs(long: Boolean) = if (long) 520L else 240L
    /** A target that moved more than this (dp) after the glide is aimed at again. */
    fun moved(x0: Float, y0: Float, x1: Float, y1: Float, density: Float) =
        kotlin.math.hypot(x1 - x0, y1 - y0) / density.coerceAtLeast(0.5f) > 24f
    /** Two quick taps close together on the pointer = "click here". */
    fun isDoubleTap(prevUpAt: Long, upAt: Long, travelPx: Float, density: Float) =
        prevUpAt > 0 && upAt - prevUpAt in 40..350 && travelPx / density.coerceAtLeast(0.5f) < 16f
    /** Touches during Blaze's own gestures aren't the user. */
    fun isOwnTouch(now: Long, ownUntil: Long) = now <= ownUntil

    // ---------- windows ----------
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var dot: Dot? = null
    private var dotLp: WindowManager.LayoutParams? = null
    private var bar: View? = null
    private var trail: Trail? = null
    @Volatile private var ownUntil = 0L
    @Volatile private var x = 0f
    @Volatile private var y = 0f
    private var density = 2f
    private var waiting: CompletableDeferred<Event>? = null
    private val hide = Runnable { if (mode == Mode.AUTO) dot?.visibility = View.GONE }
    /** Called when the user takes over (pauses) or resumes, so the chat can say so. */
    @Volatile var onChange: ((Mode) -> Unit)? = null

    private fun type() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun ensure(ctx: Context) {
        if (dot != null) return
        val w = ctx.getSystemService(WindowManager::class.java) ?: return
        wm = w
        density = ctx.resources.displayMetrics.density
        val size = (44 * density).toInt()
        val v = Dot(ctx, density)
        val lp = WindowManager.LayoutParams(size, size, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var travel = 0f; var lastUp = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> { if (!isOwnTouch(SystemClock.uptimeMillis(), ownUntil)) pause("You touched the screen"); false }
                MotionEvent.ACTION_DOWN -> {
                    pause("You took the pointer")
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; travel = 0f; true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Dragging only moves the pointer; it never clicks.
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    travel = maxOf(travel, kotlin.math.hypot(e.rawX - downX, e.rawY - downY))
                    x = lp.x + v.tipX; y = lp.y + v.tipY
                    try { w.updateViewLayout(v, lp) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    if (mode == Mode.PAUSED && isDoubleTap(lastUp, now, travel, density)) { lastUp = 0; send(Event.Click(x, y)) } else lastUp = now
                    true
                }
                else -> true
            }
        }
        try { w.addView(v, lp); dot = v; dotLp = lp } catch (_: Exception) { }
    }

    private fun place(px: Float, py: Float) {
        val v = dot ?: return; val lp = dotLp ?: return
        x = px; y = py
        lp.x = (px - v.tipX).toInt(); lp.y = (py - v.tipY).toInt()
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    /**
     * Glide to (tx, ty). False if the user paused Blaze before it got there (the caller must not act).
     * No-op (true) when the pointer is switched off.
     */
    suspend fun aim(ctx: Context, tx: Float, ty: Float): Boolean {
        if (!enabled) return mode == Mode.AUTO
        if (mode == Mode.PAUSED) return false
        withContext(Dispatchers.Main) {
            ensure(ctx)
            main.removeCallbacks(hide)
            dot?.visibility = View.VISIBLE
        }
        val fx = x.takeIf { it > 0 } ?: tx; val fy = y.takeIf { it > 0 } ?: (ty - 200 * density)
        val ms = glideMs(tx - fx, ty - fy, density)
        val start = SystemClock.uptimeMillis()
        while (true) {
            if (mode == Mode.PAUSED) return false
            val t = ((SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
            val e = 1 - (1 - t) * (1 - t) * (1 - t)   // ease out
            withContext(Dispatchers.Main) { place(fx + (tx - fx) * e, fy + (ty - fy) * e) }
            if (t >= 1f) break
            kotlinx.coroutines.delay(16)
        }
        return mode == Mode.AUTO
    }

    /** The short "here it comes" ripple. False if the user paused during it. */
    suspend fun ripple(long: Boolean): Boolean {
        if (!enabled) return mode == Mode.AUTO
        val v = dot ?: return mode == Mode.AUTO
        val ms = rippleMs(long)
        withContext(Dispatchers.Main) { v.ripple(ms) }
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { if (mode == Mode.PAUSED) return false; kotlinx.coroutines.delay(30) }
        return mode == Mode.AUTO
    }

    /** Blaze's own touch is about to happen: the pointer lets it through and doesn't count it as the user. */
    fun ownGesture(ms: Long) {
        ownUntil = SystemClock.uptimeMillis() + ms + 350
        main.post {
            val v = dot ?: return@post; val lp = dotLp ?: return@post
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
            main.postDelayed({
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
                if (mode == Mode.AUTO) { main.removeCallbacks(hide); main.postDelayed(hide, 3000) }
            }, ms + 120)
        }
    }

    /** Swipes are drawn as a path for a moment. */
    fun showPath(ctx: Context, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (!enabled) return
        main.post {
            val w = ctx.getSystemService(WindowManager::class.java) ?: return@post
            trail?.let { try { w.removeView(it) } catch (_: Exception) { } }
            val t = Trail(ctx, x1, y1, x2, y2, ctx.resources.displayMetrics.density)
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            try { w.addView(t, lp); trail = t } catch (_: Exception) { }
            main.postDelayed({ try { w.removeView(t) } catch (_: Exception) { }; if (trail === t) trail = null }, ms + 450)
        }
    }

    // ---------- manual mode ----------
    fun pause(why: String) {
        if (mode == Mode.PAUSED || !enabled) return
        mode = Mode.PAUSED
        main.post { main.removeCallbacks(hide); dot?.visibility = View.VISIBLE; dot?.paused = true; dot?.invalidate(); showBar() }
        com.blaze.assistant.device.CrashLog.write("Pointer: paused ($why)")
        onChange?.invoke(Mode.PAUSED)
    }

    private fun send(e: Event) {
        when (e) {
            is Event.Click, Event.Resume -> resumeUi()
            Event.Stop -> { resumeUi(); main.post { dot?.visibility = View.GONE } }
        }
        waiting?.complete(e) ?: run { if (e is Event.Click) pendingClick = e }
    }
    /** A double-tap that came while no tool was waiting; the next gate picks it up. */
    @Volatile private var pendingClick: Event.Click? = null

    fun resume() = send(Event.Resume)
    fun stop() = send(Event.Stop)

    private fun resumeUi() {
        mode = Mode.AUTO
        main.post { dot?.paused = false; dot?.invalidate(); bar?.let { b -> try { wm?.removeView(b) } catch (_: Exception) { } }; bar = null }
        onChange?.invoke(Mode.AUTO)
    }

    /**
     * Called before every screen action. AUTO: null, go on. PAUSED: waits for the user (15 min at most):
     * Resume → null; a double-tap → that click; Stop (or no answer) → Stop.
     */
    suspend fun gate(): Event? {
        pendingClick?.let { pendingClick = null; return it }
        if (mode == Mode.AUTO) return null
        val d = CompletableDeferred<Event>()
        waiting = d
        val e = withTimeoutOrNull(15 * 60_000L) { d.await() } ?: Event.Stop.also { resumeUi() }
        waiting = null
        return if (e == Event.Resume) null else e
    }

    /** When Blaze finishes or stops, the pointer leaves the screen and manual mode ends. */
    fun done() {
        mode = Mode.AUTO; pendingClick = null; waiting?.complete(Event.Stop); waiting = null
        main.post {
            bar?.let { b -> try { wm?.removeView(b) } catch (_: Exception) { } }; bar = null
            dot?.let { d -> try { wm?.removeView(d) } catch (_: Exception) { } }; dot = null; dotLp = null
            x = 0f; y = 0f
        }
    }

    private fun showBar() {
        if (bar != null) return
        val v = dot ?: return
        val ctx = v.context
        val w = wm ?: return
        val d = density
        fun chip(text: String, fill: Boolean, onTap: () -> Unit) = TextView(ctx).apply {
            this.text = text; textSize = 13f; setTextColor(if (fill) Color.WHITE else Color.parseColor("#1D1A16"))
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            setBackgroundColor(if (fill) ACCENT else Color.TRANSPARENT)
            setOnClickListener { onTap() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFFAF2")); elevation = 6 * d
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            addView(TextView(ctx).apply { text = "Paused · drag, double-tap to click"; textSize = 12f; setTextColor(Color.parseColor("#6B645B"))
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0) })
            addView(chip("Resume", true) { resume() })
            addView(chip("Stop", false) { stop() })
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (36 * d).toInt() }
        try { w.addView(row, lp); bar = row } catch (_: Exception) { }
    }

    /** The pointer: an arrow with its tip on the target, Blaze blue with a white edge; a ring while rippling. */
    private class Dot(ctx: Context, val d: Float) : View(ctx) {
        val tipX = 6 * d; val tipY = 6 * d
        var paused = false
        private var rippleStart = 0L; private var rippleMs = 0L
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeJoin = Paint.Join.ROUND }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
        private val arrow = Path().apply {
            moveTo(tipX, tipY); lineTo(tipX, tipY + 22 * d); lineTo(tipX + 6 * d, tipY + 16.5f * d)
            lineTo(tipX + 10 * d, tipY + 25 * d); lineTo(tipX + 14 * d, tipY + 23 * d); lineTo(tipX + 10 * d, tipY + 14.5f * d)
            lineTo(tipX + 17 * d, tipY + 14.5f * d); close()
        }
        fun ripple(ms: Long) { rippleStart = SystemClock.uptimeMillis(); rippleMs = ms; invalidate() }
        override fun onDraw(c: Canvas) {
            val t = if (rippleMs > 0) (SystemClock.uptimeMillis() - rippleStart).toFloat() / rippleMs else 2f
            if (t <= 1f) {
                ring.alpha = ((1 - t) * 255).toInt()
                c.drawCircle(tipX, tipY, (4 + 14 * t) * d, ring)
                postInvalidateOnAnimation()
            } else rippleMs = 0
            fill.color = if (paused) Color.parseColor("#E8743B") else ACCENT
            c.drawPath(arrow, fill); c.drawPath(arrow, edge)
        }
    }

    /** A swipe drawn as a fading line with a dot at the start. */
    private class Trail(ctx: Context, val x1: Float, val y1: Float, val x2: Float, val y2: Float, d: Float) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; alpha = 170; style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; alpha = 200 }
        private val r = 6 * d
        override fun onDraw(c: Canvas) { c.drawLine(x1, y1, x2, y2, line); c.drawCircle(x1, y1, r, dotP) }
    }
}
