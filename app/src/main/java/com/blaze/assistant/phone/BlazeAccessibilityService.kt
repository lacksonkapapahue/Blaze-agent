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

    fun currentApp(): String? = rootInActiveWindow?.packageName?.toString()?.let { appLabel(it) }

    suspend fun tap(id: Int, long: Boolean = false): String {
        val node = element(id) ?: return "No element [$id]. Call read_screen again to get fresh numbers."
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            val ok = if (long) n.isLongClickable else n.isClickable
            if (ok && n.performAction(action)) return "Tapped [$id]."
            n = n.parent
        }
        val r = Rect(); node.getBoundsInScreen(r)
        return if (gestureTap(r.exactCenterX(), r.exactCenterY(), if (long) 800 else 60)) "Tapped [$id] by touch."
        else "Couldn't tap [$id]."
    }

    suspend fun type(id: Int, text: String): String {
        val node = element(id) ?: return "No element [$id]. Call read_screen again."
        if (!node.isEditable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            settle(400, 120)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed into [$id]."
        else "Couldn't type into [$id]; it may not be a text field."
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
