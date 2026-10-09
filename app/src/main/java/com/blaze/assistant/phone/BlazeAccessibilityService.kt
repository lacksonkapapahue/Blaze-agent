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
    private val elements = mutableListOf<AccessibilityNodeInfo?>()

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
        try { Watchers.onEvent(this, event.packageName?.toString().orEmpty()) } catch (_: Exception) { }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != lastWindowPkg && pkg != packageName) {
                lastWindowPkg = pkg
                com.blaze.assistant.agent.ScreenMemory.onWindow(this, pkg)
                try { com.blaze.assistant.proactive.Triggers.onApp(pkg, appLabel(pkg)) } catch (_: Exception) { }
                try { com.blaze.assistant.proactive.Habits.onApp(pkg, appLabel(pkg)) } catch (_: Exception) { }
            }
        }
    }

    /**
     * Wait until the screen has been quiet for [quietMs] (no content changes), at most [maxMs].
     * Much faster than a fixed sleep when the app reacts quickly.
     */
    suspend fun settle(maxMs: Long = 1500, quietMs: Long = 250) {
        val start = android.os.SystemClock.uptimeMillis()
        val pkg = lastWindowPkg
        // Learned per app: how long this app usually takes to go quiet. Fast apps stop waiting early.
        val learned = settleAvg[pkg]
        val cap = if (learned != null && (settleN[pkg] ?: 0) >= 3) minOf(maxMs, maxOf(350L, (learned * 2.2).toLong())) else maxMs
        val quiet = if (learned != null && learned < 300) minOf(quietMs, 150L) else quietMs
        kotlinx.coroutines.delay(60)
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEventAt >= quiet || now - start >= cap) {
                val took = (lastEventAt - start).coerceIn(0, maxMs)
                if (pkg.isNotEmpty()) {
                    settleAvg[pkg] = settleAvg[pkg]?.let { it * 0.7 + took * 0.3 } ?: took.toDouble()
                    settleN[pkg] = (settleN[pkg] ?: 0) + 1
                }
                return
            }
            kotlinx.coroutines.delay(40)
        }
    }
    private val settleAvg = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val settleN = java.util.concurrent.ConcurrentHashMap<String, Int>()
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

    // Stable element numbers: an element keeps its number across reads of the same screen, so the
    // model can act on numbers it already has and after-action reads only need to send the changes.
    private var keyToId = HashMap<String, Int>()
    private var lastLines = HashMap<Int, String>()
    private var lastReadPkg = ""
    private var lastReadAt = 0L

    private fun role(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString()?.substringAfterLast('.') ?: "View"
        return when {
            n.isEditable || c.contains("EditText") -> "field"
            c.contains("Switch") || c.contains("Toggle") -> "switch"
            c.contains("CheckBox") -> "check"
            c.contains("Radio") -> "radio"
            c.contains("Button") || (c.contains("Image") && n.isClickable) -> "btn"
            c.contains("Image") -> "img"
            c.contains("Recycler") || c.contains("ListView") || c.contains("ScrollView") || c.contains("Pager") -> "list"
            c.contains("Web") -> "web"
            c == "TextView" -> if (n.isClickable) "link" else "text"
            n.isClickable -> "item"
            else -> "text"
        }
    }

    /** Full read. With [diff], a read of the same screen returns only what changed. */
    fun readScreen(diff: Boolean = false): String {
        val root = rootInActiveWindow ?: run { elements.clear(); return "I can't see the screen right now (no active window)." }
        val pkg = root.packageName?.toString() ?: "unknown"
        val found = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        walk(root, found, 0)
        // Keys: what the element is and says (not where), with a counter for repeats.
        val seen = HashMap<String, Int>()
        val keys = found.map { (n, line) ->
            val base = role(n) + "|" + (n.text ?: "") + "|" + (n.contentDescription ?: "") + "|" + (n.viewIdResourceName ?: "")
            val k = seen.getOrDefault(base, 0); seen[base] = k + 1
            "$base#$k"
        }
        val now = android.os.SystemClock.uptimeMillis()
        val samePkg = pkg == lastReadPkg && now - lastReadAt < 120_000
        val overlap = if (samePkg) keys.count { it in keyToId } else 0
        val keep = samePkg && found.isNotEmpty() && overlap * 10 >= found.size * 3
        val newMap = HashMap<String, Int>()
        val used = HashSet<Int>()
        if (keep) keys.forEach { k -> keyToId[k]?.let { if (used.add(it)) newMap[k] = it } }
        var next = if (keep) (keyToId.values.maxOrNull() ?: -1) + 1 else 0
        keys.forEach { k -> if (k !in newMap) { while (next in used) next++; newMap[k] = next; used.add(next); next++ } }
        elements.clear()
        val lines = HashMap<Int, String>()
        found.forEachIndexed { i, (n, line) ->
            val id = newMap[keys[i]]!!
            while (elements.size <= id) elements.add(null)
            elements[id] = n
            lines[id] = "[$id] $line"
        }
        val prevLines = lastLines
        keyToId = newMap; lastLines = lines; lastReadPkg = pkg; lastReadAt = now
        val sb = StringBuilder()
        val ordered = found.indices.map { newMap[keys[it]]!! }
        if (diff && keep && prevLines.isNotEmpty()) {
            val changed = ordered.filter { prevLines[it] != lines[it] }
            val gone = prevLines.keys.filter { it !in lines }
            if (changed.isEmpty() && gone.isEmpty()) return "Same screen in ${appLabel(pkg)}, nothing changed."
            if (changed.size + gone.size < found.size / 2 + 1) {
                sb.append("Same screen in ").append(appLabel(pkg)).append(". Changed or new:\n")
                changed.forEach { sb.append(lines[it]).append("\n") }
                if (gone.isNotEmpty()) sb.append("Gone: ").append(gone.take(20).joinToString(" ") { "[$it]" }).append("\n")
                sb.append("Other ").append(found.size - changed.size).append(" elements unchanged, same numbers.")
                return sb.toString()
            }
        }
        sb.append(appLabel(pkg)).append(" (").append(pkg).append("). Elements, use the [number]:\n")
        ordered.forEach { sb.append(lines[it]).append("\n") }
        if (found.isEmpty()) sb.append("(nothing readable on screen)\n")
        val out = sb.toString()
        return if (out.length <= 6_000) out else out.take(6_000) + "\n…(more below; scroll to see it)"
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Pair<AccessibilityNodeInfo, String>>, depth: Int) {
        if (node == null || depth > 40 || out.size >= 200) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable ||
            node.isEditable || node.isScrollable || node.isCheckable
        // Skip a bare clickable wrapper whose only job is to hold one labelled child: the child is listed.
        val wrapper = text.isEmpty() && desc.isEmpty() && !node.isEditable && !node.isScrollable && !node.isCheckable &&
            node.childCount == 1 && node.getChild(0)?.let { c -> !c.isClickable && (!c.text.isNullOrBlank() || !c.contentDescription.isNullOrBlank()) } == true
        if (interesting && !wrapper) {
            val sb = StringBuilder(role(node))
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append("\"")
            if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(80)).append(")")
            if (hint.isNotEmpty() && hint != text) sb.append(" hint \"").append(hint.take(60)).append("\"")
            if (node.isCheckable) sb.append(if (node.isChecked) " on" else " off")
            if (node.isScrollable) sb.append(" scrolls")
            if (node.isSelected) sb.append(" selected")
            if (!node.isEnabled) sb.append(" disabled")
            if (node.isClickable && role(node) in setOf("text", "img")) sb.append(" tappable")
            out.add(node to sb.toString())
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, depth + 1)
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
        val labels = elements.indices.map { if (elements[it] == null) "" else labelOf(it).trim().lowercase() }
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
            if (n == null) return@forEachIndexed
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
        return elements.indexOfFirst { it?.isEditable == true }.takeIf { it >= 0 }
    }

    /** Presses the keyboard's Enter/Search on the current field. */
    suspend fun pressEnter(): Boolean { val id = anyField() ?: return false; return submitIme(elements[id] ?: return false) }

    /** Package in front right now. */
    fun currentPkg(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Words visible on screen (for checking a step worked, and short "what changed" notes). */
    fun visibleWords(): Set<String> {
        readScreen()
        return elements.filterNotNull().flatMap { labelsOf(it) }.map { it.take(80) }.toSet()
    }

    /** A soft ring where Blaze is about to tap, so you can watch it work. */
    fun flash(id: Int) {
        if (!com.blaze.assistant.agent.Settings.bool("show_taps", true) || com.blaze.assistant.agent.Settings.bool("speed_mode", false)) return
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

    /** Tap a node found outside read_screen (watchers): accessibility click up the parent chain, else a touch. */
    suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) { if (n.isClickable && n.isEnabled) { if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; break }; n = n.parent }
        val pt = centerOf(node) ?: return false
        return gestureTap(pt.first, pt.second, 60)
    }

    /**
     * Flip a Quick Settings tile (Wi-Fi, Bluetooth, Airplane...) like a person would: pull down the
     * shade, find the tile by any of its names, tap only if it isn't already in the wanted state, close.
     * Works on any skin because it matches labels, not positions. Null when the tile can't be found.
     */
    suspend fun toggleQuickTile(names: List<String>, want: Boolean?): String? {
        fun norm(s: CharSequence?) = s?.toString()?.lowercase()?.replace('\u2011', '-')?.replace('\u2010', '-')?.trim().orEmpty()
        val keys = names.map { norm(it) }
        fun find(): AccessibilityNodeInfo? {
            val roots = (try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }) + listOfNotNull(rootInActiveWindow)
            for (r in roots) {
                val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(r) }
                var seen = 0
                while (stack.isNotEmpty() && seen++ < 3000) {
                    val n = stack.removeLast()
                    val label = norm(n.contentDescription).ifEmpty { norm(n.text) }
                    if (label.isNotEmpty() && keys.any { label == it || label.startsWith("$it,") || label.startsWith("$it ") }) {
                        var c: AccessibilityNodeInfo? = n
                        while (c != null && !c.isClickable) c = c.parent
                        return c ?: n
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
                }
            }
            return null
        }
        fun state(n: AccessibilityNodeInfo): Boolean? {
            if (n.isCheckable) return n.isChecked
            val sd = if (android.os.Build.VERSION.SDK_INT >= 30) norm(n.stateDescription) else ""
            val all = "$sd ${norm(n.contentDescription)}"
            return when { Regex("\\b(on|enabled|connected)\\b").containsMatchIn(all) -> true; Regex("\\b(off|disabled)\\b").containsMatchIn(all) -> false; else -> null }
        }
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) return null
        var tile: AccessibilityNodeInfo? = null
        repeat(8) { if (tile == null) { delay(250); tile = find() } }
        val t = tile ?: run { performGlobalAction(GLOBAL_ACTION_BACK); return null }
        val before = state(t)
        val name = names.first()
        if (want != null && before == want) { performGlobalAction(GLOBAL_ACTION_BACK); return "$name is already ${if (want) "on" else "off"}." }
        if (!tapNode(t)) { performGlobalAction(GLOBAL_ACTION_BACK); return null }
        delay(600)
        val after = find()?.let { it.refresh(); state(it) }
        performGlobalAction(GLOBAL_ACTION_BACK)
        return when {
            after != null -> "$name ${if (after) "on" else "off"}."
            want != null -> "$name ${if (want) "on" else "off"}."
            else -> "Flipped $name."
        }
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
                elements.firstOrNull { it?.isEditable == true }?.let { node = it }
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
        val target = id?.let { element(it) } ?: elements.firstOrNull { it?.isScrollable == true }
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
        return dispatch(GestureDescription.StrokeDescription(path, 0, if (com.blaze.assistant.agent.Settings.bool("speed_mode", false)) 180 else 350))
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
