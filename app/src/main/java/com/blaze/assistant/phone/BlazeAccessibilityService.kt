package com.blaze.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Blaze's "hands": reads what is on screen and taps / types / scrolls.
 * The user turns it on in Settings > Accessibility > Blaze phone control.
 */
class BlazeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: BlazeAccessibilityService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(ctx.packageName + "/")
        }
    }

    /** Elements from the last read_screen, addressed by their number. */
    private val elements = mutableListOf<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        com.blaze.assistant.voice.WakeWordService.start(this)
        if (com.blaze.assistant.agent.Settings.orbPinned) com.blaze.assistant.ui.Overlay.showOrb(this)
    }

    /** Time of the last screen change, so actions can wait for the UI to settle instead of sleeping. */
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastWindowPkg = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventAt = android.os.SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != lastWindowPkg && pkg != packageName) {
                lastWindowPkg = pkg
                com.blaze.assistant.agent.ScreenMemory.onWindow(this, pkg)
                try { com.blaze.assistant.proactive.Triggers.onApp(pkg, appLabel(pkg)) } catch (_: Exception) { }
            }
        }
    }

    /**
     * Wait until the screen has been quiet for [quietMs] (no content changes), at most [maxMs].
     * Much faster than a fixed sleep when the app reacts quickly.
     */
    suspend fun settle(maxMs: Long = 1500, quietMs: Long = 250) {
        val start = android.os.SystemClock.uptimeMillis()
        kotlinx.coroutines.delay(80)
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEventAt >= quietMs || now - start >= maxMs) return
            kotlinx.coroutines.delay(50)
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        com.blaze.assistant.ui.Overlay.hideOrb(this, unpin = false)
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- Seeing ----------

    /** Screenshot of the default display, downscaled, as a data:image/jpeg URL. Android 11+. */
    suspend fun screenshot(): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val bmp = suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(r: ScreenshotResult) {
                            val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                            val sw = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            r.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(sw)
                        }
                        override fun onFailure(code: Int) { if (cont.isActive) cont.resume(null) }
                    })
            } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
        } ?: return null
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // ---------- Reading ----------

    fun readScreen(): String {
        elements.clear()
        val root = rootInActiveWindow ?: return "I can't see the screen right now (no active window)."
        val pkg = root.packageName?.toString() ?: "unknown"
        val sb = StringBuilder()
        sb.append("Foreground app: ").append(appLabel(pkg)).append(" (").append(pkg).append(")\n")
        sb.append("Elements (use the number in [ ] with tap/type/scroll):\n")
        walk(root, sb, 0)
        if (elements.isEmpty()) sb.append("(nothing readable on screen)\n")
        val out = sb.toString()
        return if (out.length <= 6_000) out else out.take(6_000) + "\n…(more below; scroll to see it)"
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 40 || elements.size >= 200) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable ||
            node.isEditable || node.isScrollable || node.isCheckable
        if (interesting) {
            val id = elements.size
            elements.add(node)
            val kind = node.className?.toString()?.substringAfterLast('.') ?: "View"
            sb.append("[").append(id).append("] ").append(kind)
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append("\"")
            if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(80)).append("\"")
            if (hint.isNotEmpty() && hint != text) sb.append(" hint=\"").append(hint.take(60)).append("\"")
            val flags = mutableListOf<String>()
            if (node.isClickable) flags += "clickable"
            if (node.isEditable) flags += "editable"
            if (node.isScrollable) flags += "scrollable"
            if (node.isCheckable) flags += if (node.isChecked) "checked" else "unchecked"
            if (node.isSelected) flags += "selected"
            if (!node.isEnabled) flags += "disabled"
            if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(", ")).append(")")
            sb.append("\n")
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), sb, depth + 1)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun element(id: Int): AccessibilityNodeInfo? = elements.getOrNull(id)

    // ---------- Acting ----------

    /** The visible words of element [id], used to spot risky taps like "Send" or "Pay". */
    /** After read_screen: the element whose text or description matches [label] best (for task replay). */
    fun findByLabel(label: String): Int? {
        val l = label.trim().lowercase(); if (l.isEmpty()) return null
        val labels = elements.indices.map { labelOf(it).trim().lowercase() }
        labels.indexOfFirst { it == l }.takeIf { it >= 0 }?.let { return it }
        return labels.indexOfFirst { it.isNotEmpty() && (it.contains(l) || l.contains(it)) }.takeIf { it >= 0 }
    }

    fun labelOf(id: Int): String {
        val n = element(id) ?: return ""
        val own = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ").trim()
        if (own.isNotEmpty()) return own
        for (i in 0 until n.childCount) n.getChild(i)?.let { c ->
            val t = listOfNotNull(c.text?.toString(), c.contentDescription?.toString()).joinToString(" ").trim()
            if (t.isNotEmpty()) return t
        }
        return n.viewIdResourceName?.substringAfter('/')?.replace('_', ' ') ?: ""
    }

    // ---------- v1.9: by-label targeting for planned scripts ----------

    private fun labelsOf(n: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        n.viewIdResourceName?.substringAfter('/')?.replace('_', ' ')?.takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /**
     * Fresh read, then the element best matching [label]: exact text, then word-start, then
     * contains; editable fields win when [wantField], clickable things otherwise.
     */
    fun findTarget(label: String, wantField: Boolean = false): Int? {
        readScreen()
        val l = label.trim().lowercase(); if (l.isEmpty()) return null
        var best = -1; var bestScore = 0
        elements.forEachIndexed { i, n ->
            val ls = labelsOf(n).map { it.lowercase() } + labelOf(i).lowercase()
            var sc = 0
            for (x in ls) sc = maxOf(sc, when {
                x == l -> 100
                x.startsWith(l) || Regex("\\b" + Regex.escape(l)).containsMatchIn(x) -> 70
                x.contains(l) -> 50
                l.contains(x) && x.length >= 3 -> 30
                else -> 0 })
            if (sc == 0) return@forEachIndexed
            if (wantField && n.isEditable) sc += 25
            if (!wantField && n.isClickable) sc += 10
            if (!n.isEnabled) sc -= 40
            if (sc > bestScore) { bestScore = sc; best = i }
        }
        return best.takeIf { it >= 0 && bestScore >= 30 }
    }

    /** Waits up to [ms] for [label] to show up (apps load lazily). */
    suspend fun waitFor(label: String, ms: Long, wantField: Boolean = false): Int? {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (true) {
            findTarget(label, wantField)?.let { return it }
            if (android.os.SystemClock.uptimeMillis() > end) return null
            kotlinx.coroutines.delay(150)
        }
    }

    /** The focused text field, or the first one on screen. */
    fun anyField(): Int? {
        readScreen()
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) { val i = elements.indexOfFirst { it == f }; if (i >= 0) return i }
        return elements.indexOfFirst { it.isEditable }.takeIf { it >= 0 }
    }

    /** Presses the keyboard's Enter/Search on the current field. */
    suspend fun pressEnter(): Boolean { val id = anyField() ?: return false; return submitIme(elements[id]) }

    /** Package in front right now. */
    fun currentPkg(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Words visible on screen (for checking a step worked, and short "what changed" notes). */
    fun visibleWords(): Set<String> {
        readScreen()
        return elements.flatMap { labelsOf(it) }.map { it.take(80) }.toSet()
    }

    /** A soft ring where Blaze is about to tap, so you can watch it work. */
    fun flash(id: Int) {
        if (!com.blaze.assistant.agent.Settings.bool("show_taps", true)) return
        val n = element(id) ?: return
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.width() <= 2 || r.height() <= 2) return
        try {
            val wm = getSystemService(android.view.WindowManager::class.java)
            val pad = (8 * resources.displayMetrics.density).toInt()
            val v = object : android.view.View(this) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density; color = 0xFF6B8CFF.toInt() }
                val f = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x226B8CFF }
                override fun onDraw(c: android.graphics.Canvas) {
                    val h = p.strokeWidth
                    c.drawRect(h, h, width - h, height - h, f); c.drawRect(h, h, width - h, height - h, p)
                }
            }
            val lp = android.view.WindowManager.LayoutParams(r.width() + pad * 2, r.height() + pad * 2,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = r.left - pad; y = r.top - pad }
            android.os.Handler(mainLooper).post { try { wm.addView(v, lp); v.animate().alpha(0f).setStartDelay(260).setDuration(320).withEndAction { try { wm.removeView(v) } catch (_: Exception) { } }.start() } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }

    fun currentApp(): String? = rootInActiveWindow?.packageName?.toString()?.let { appLabel(it) }

    /** True if the screen changed (any accessibility event) since [since], waiting up to [ms]. */
    private suspend fun changedSince(since: Long, ms: Long): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (lastEventAt > since) return true
            kotlinx.coroutines.delay(40)
        }
        return lastEventAt > since
    }

    private fun centerOf(n: AccessibilityNodeInfo): Pair<Float, Float>? {
        val r = Rect(); n.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        r.intersect(0, 0, dm.widthPixels, dm.heightPixels)
        if (r.width() <= 2 || r.height() <= 2) return null
        return r.exactCenterX() to r.exactCenterY()
    }

    /**
     * Tap an element. Uses the accessibility click on the element or its nearest clickable parent,
     * then checks the screen actually reacted; if not (custom views often ignore it), it touches
     * the middle of the element like a finger would.
     */
    suspend fun tap(id: Int, long: Boolean = false): String {
        val node = element(id) ?: return "No element [$id]. Call read_screen again to get fresh numbers."
        node.refresh()
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = node
        var target: AccessibilityNodeInfo? = null
        while (n != null) {
            if (if (long) n.isLongClickable else n.isClickable) { target = n; break }
            n = n.parent
        }
        val before = android.os.SystemClock.uptimeMillis()
        if (target != null && target.isEnabled && target.performAction(action)) {
            if (changedSince(before, 700)) return "Tapped [$id]."
        }
        val pt = centerOf(node) ?: target?.let { centerOf(it) } ?: return "Couldn't tap [$id]: it's off screen. Scroll first."
        return if (gestureTap(pt.first, pt.second, if (long) 800 else 60)) "Tapped [$id] by touch."
        else "Couldn't tap [$id]."
    }

    /** Fresh read, then tap the element whose words match [label]. Immune to stale numbers. */
    suspend fun tapText(label: String, long: Boolean = false): String {
        readScreen()
        val id = findByLabel(label) ?: return "No element with text \"$label\" on screen. Read the screen or scroll."
        return tap(id, long).replace("[$id]", "\"${label.take(40)}\"")
    }

    suspend fun type(id: Int, text: String, submit: Boolean = false): String {
        var node = element(id) ?: return "No element [$id]. Call read_screen again."
        if (!node.isEditable) {
            // Search icons/bars often turn into a real field after a tap.
            tap(id)
            settle(700, 150)
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) node = focused
            else {
                readScreen()
                elements.firstOrNull { it.isEditable }?.let { node = it }
            }
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Couldn't type into [$id]; it may not be a text field."
        if (!submit) return "Typed into [$id]."
        return "Typed into [$id] and " + (if (submitIme(node)) "pressed Enter/Search." else "couldn't press Enter; tap the search/send button.")
    }

    /** Presses the keyboard's Enter / Search key on a field (Android 11+). */
    private suspend fun submitIme(node: AccessibilityNodeInfo): Boolean {
        settle(300, 100)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            node.refresh()
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }
        return false
    }

    suspend fun scroll(direction: String, id: Int?): String {
        val forward = direction.lowercase() in listOf("down", "right", "forward")
        val target = id?.let { element(it) } ?: elements.firstOrNull { it.isScrollable }
        if (target != null) {
            var n: AccessibilityNodeInfo? = target
            while (n != null) {
                if (n.isScrollable && n.performAction(
                        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    )
                ) return "Scrolled $direction."
                n = n.parent
            }
        }
        // Fall back to a swipe in the middle of the screen.
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dy = dm.heightPixels * 0.3f
        val dx = dm.widthPixels * 0.3f
        val ok = when (direction.lowercase()) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy)
            "up" -> swipe(cx, cy - dy, cx, cy + dy)
            "right" -> swipe(cx + dx, cy, cx - dx, cy)
            else -> swipe(cx - dx, cy, cx + dx, cy)
        }
        return if (ok) "Swiped to scroll $direction." else "Couldn't scroll."
    }

    fun press(button: String): String {
        val action = when (button.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return "Unknown button '$button'."
        }
        return if (performGlobalAction(action)) "Pressed $button." else "Couldn't press $button."
    }

    private suspend fun gestureTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, 350))
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
}
