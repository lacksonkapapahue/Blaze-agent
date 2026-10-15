package com.blaze.assistant.phone

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Magic Hand (v0.24.7), Android side of "what's under the tip": the deepest visible node at a screen point (Blaze's
 * own overlays skipped), its child-index path from the window root (for watches), re-reading a watched node,
 * the text inside a region, a screenshot crop and on-device OCR (ML Kit, bundled Latin model: no network).
 */
object MagicHandScreen {
    class Hit(val node: AccessibilityNodeInfo, val element: MagicHandLogic.Element, val box: MagicHandLogic.Box,
              val path: List<Int>, val pkg: String) {
        val kind: MagicHandLogic.Kind get() = MagicHandLogic.kindOf(element)
    }

    private fun roots(svc: BlazeAccessibilityService): List<AccessibilityNodeInfo> = try {
        svc.windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull { it.root }
            .filter { it.packageName?.toString() != svc.packageName }
    } catch (_: Exception) { emptyList() }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }

    private fun box(n: AccessibilityNodeInfo): MagicHandLogic.Box { val r = Rect(); n.getBoundsInScreen(r); return MagicHandLogic.Box(r.left, r.top, r.right, r.bottom) }

    fun elementOf(n: AccessibilityNodeInfo): MagicHandLogic.Element {
        val text = n.text?.toString().orEmpty().ifBlank { childText(n).orEmpty() }
        return MagicHandLogic.Element(
            text = if (n.isPassword) "" else text.take(2000),
            desc = n.contentDescription?.toString().orEmpty(),
            className = n.className?.toString().orEmpty(),
            viewId = n.viewIdResourceName.orEmpty(),
            clickable = n.isClickable,
            editable = n.isEditable,
            password = n.isPassword,
            url = n.text?.toString()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty(),
        )
    }

    private fun childText(n: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 3) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.isPassword) continue
            (c.text ?: c.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            childText(c, depth + 1)?.let { return it }
        }
        return null
    }

    /** The deepest visible node with something to say at (x, y). */
    fun at(svc: BlazeAccessibilityService, x: Int, y: Int): Hit? {
        for (root in roots(svc)) {
            if (!box(root).contains(x, y)) continue
            var cur = root
            val path = ArrayList<Int>()
            var guard = 0
            while (guard++ < 60) {
                var nextIdx = -1
                var next: AccessibilityNodeInfo? = null
                // Topmost child first (later children draw on top).
                for (i in cur.childCount - 1 downTo 0) {
                    val c = cur.getChild(i) ?: continue
                    if (c.isVisibleToUser && box(c).contains(x, y)) { next = c; nextIdx = i; break }
                }
                if (next == null) break
                cur = next; path += nextIdx
            }
            // Walk back up to the nearest node that has a label (a bare container isn't useful).
            var n: AccessibilityNodeInfo? = cur
            var p = path.toMutableList()
            while (n != null && elementOf(n).label.isBlank() && !n.isEditable && p.isNotEmpty()) { n = n.parent; p = p.dropLast(1).toMutableList() }
            val node = n ?: cur
            return Hit(node, elementOf(node), box(node), p, root.packageName?.toString().orEmpty())
        }
        return null
    }

    /** Finds a watched node again: by view id first (when it's unique), else by its path. Null when not on screen. */
    fun reread(svc: BlazeAccessibilityService, w: MagicHandWatch.Watch): String? {
        val root = roots(svc).firstOrNull { it.packageName?.toString() == w.pkg } ?: return null
        if (w.viewId.isNotBlank()) {
            val found = try { root.findAccessibilityNodeInfosByViewId(w.viewId) } catch (_: Exception) { emptyList() }
            if (found.size == 1) return elementOf(found[0]).let { it.text.ifBlank { it.desc } }
        }
        var cur: AccessibilityNodeInfo = root
        for (i in w.path) cur = cur.getChild(i) ?: return null
        if (w.className.isNotBlank() && cur.className?.toString() != w.className) return null
        return elementOf(cur).let { it.text.ifBlank { it.desc } }
    }

    /** Text of every visible node inside [b] (the fallback when OCR finds nothing), top to bottom. */
    fun textIn(svc: BlazeAccessibilityService, b: MagicHandLogic.Box): String {
        val out = ArrayList<Pair<Int, String>>()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40 || n.isPassword) return
            val nb = box(n)
            if (nb.r <= b.l || nb.l >= b.r || nb.b <= b.t || nb.t >= b.b) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && nb.cx in b.l..b.r && nb.cy in b.t..b.b }?.let { out += nb.t to it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        roots(svc).forEach { walk(it, 0) }
        return out.sortedBy { it.first }.map { it.second }.distinct().joinToString("\n")
    }

    /**
     * v0.27.0: the screen boxes of the text blocks around [b] (nodes with text or a description, visible, not
     * passwords), for snapping the grab box to the text on release. At most [max] boxes.
     */
    fun textBoxes(svc: BlazeAccessibilityService, b: MagicHandLogic.Box, max: Int = 200): List<MagicHandLogic.Box> {
        val out = ArrayList<MagicHandLogic.Box>()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40 || out.size >= max || n.isPassword || !n.isVisibleToUser) return
            val nb = box(n)
            if (nb.r <= b.l || nb.l >= b.r || nb.b <= b.t || nb.t >= b.b) return
            val hasText = !n.text.isNullOrBlank() || (n.childCount == 0 && !n.contentDescription.isNullOrBlank())
            if (hasText && !nb.isEmpty()) out += nb
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        roots(svc).forEach { walk(it, 0) }
        return out
    }

    /** A full-resolution screenshot (Android 11+), or null. */
    suspend fun screenshot(svc: BlazeAccessibilityService): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            try {
                svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, svc.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(r: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                            val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            r.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(sw)
                        }
                        override fun onFailure(code: Int) { if (cont.isActive) cont.resume(null) }
                    })
            } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }

    fun crop(bmp: Bitmap, b: MagicHandLogic.Box): Bitmap? {
        val l = b.l.coerceIn(0, bmp.width - 1); val t = b.t.coerceIn(0, bmp.height - 1)
        val w = (b.r.coerceAtMost(bmp.width) - l); val h = (b.b.coerceAtMost(bmp.height) - t)
        if (w <= 1 || h <= 1) return null
        return try { Bitmap.createBitmap(bmp, l, t, w, h) } catch (_: Exception) { null }
    }

    /** JPEG data URL, at most [maxSide] px a side. */
    fun dataUrl(bmp: Bitmap, maxSide: Int = 1024): String {
        val s = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** On-device OCR of [bmp] (ML Kit's bundled Latin model). Empty on failure. */
    suspend fun ocr(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        try {
            val rec = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r -> rec.close(); if (cont.isActive) cont.resume(r.text.orEmpty()) }
                .addOnFailureListener { rec.close(); if (cont.isActive) cont.resume("") }
        } catch (_: Throwable) { if (cont.isActive) cont.resume("") }
    }
}
