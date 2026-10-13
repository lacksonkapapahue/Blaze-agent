package com.blaze.assistant.phone

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Attachment
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.browser.PointerActions
import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Gesture
import com.blaze.assistant.phone.MagicHandLogic.Intent as HandIntent
import com.blaze.assistant.phone.MagicHandState.DockButton
import com.blaze.assistant.phone.MagicHandState.GrabAction
import com.blaze.assistant.phone.MagicHandState.Panel
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MascotArt
import com.blaze.assistant.ui.OverlayOwner
import com.blaze.assistant.ui.composeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Magic Hand (v0.24.7): Blaze's mascot as a pointer the user moves, summoned from the hand button in the quick
 * assistant panel (hold Home). Three accessibility overlay windows: a non-touchable highlight layer, the hand (drag it;
 * the flame tip points), and the dock (status, menu, answer, confirm, grab, teach, Close).
 *
 * Rules: dragging only highlights; nothing is ever tapped or typed without a yes on the confirm card; closing the
 * dock stops everything (windows, the question in flight, carrying, teach recording); in Private
 * ([MagicHandGuard]) nothing is captured, sent or saved.
 */
object MagicHand {
    private val _ui = MutableStateFlow(MagicHandState.Ui())
    val ui: StateFlow<MagicHandState.Ui> = _ui
    val on: Boolean get() = _ui.value.on

    private val main = Handler(Looper.getMainLooper())
    private var svc: BlazeAccessibilityService? = null
    private var wm: WindowManager? = null
    private var scope: CoroutineScope? = null
    private var hand: HandFrame? = null
    private var handLp: WindowManager.LayoutParams? = null
    private var handOwner: OverlayOwner? = null
    private var hi: HighlightView? = null
    private var dock: View? = null
    private var dockLp: WindowManager.LayoutParams? = null
    private var dockOwner: OverlayOwner? = null
    private val lit = mutableStateOf(false)
    private val carryingState = mutableStateOf(false)

    private var lastHit: MagicHandScreen.Hit? = null
    private var pendingHit: MagicHandScreen.Hit? = null
    private var recorder: MagicHandTeach.Recorder? = null
    private var askJob: Job? = null
    private var askedAgent = false
    private var ignoreClicksUntil = 0L
    private var density = 2f

    const val HAND_DP = 64

    private fun set(f: (MagicHandState.Ui) -> MagicHandState.Ui) {
        val n = f(_ui.value); _ui.value = n
        carryingState.value = n.carrying
        updateDockFocus(n.panel == Panel.TEACH_REVIEW)
    }

    /** Private chat, or a private tab in Blaze's browser while it's in front. */
    fun isPrivate(): Boolean {
        if (Agent.privateChat.value) return true
        val s = svc ?: BlazeAccessibilityService.instance
        return try {
            com.blaze.assistant.browser.BrowserEngine.tab()?.isPrivate == true && s != null && s.currentPkg() == s.packageName
        } catch (_: Throwable) { false }
    }

    /** From the assistant panel's hand button. Returns a line to show when it can't start (phone control off), else null. */
    fun summon(ctx: Context): String? {
        val s = BlazeAccessibilityService.instance ?: return MagicHandState.NEEDS_A11Y
        main.postDelayed({ start(s) }, 250)
        return null
    }

    private fun start(s: BlazeAccessibilityService) {
        if (on) return
        svc = s
        val w = s.getSystemService(WindowManager::class.java) ?: return
        wm = w
        density = s.resources.displayMetrics.density
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        _ui.value = MagicHandState.start(isPrivate())
        addHighlight(s, w)
        addDock(s, w)
        addHand(s, w)
    }

    /** Closing the dock: everything stops and every window goes. */
    fun stop() {
        main.post {
            if (askedAgent && askJob?.isActive == true && Agent.mood.value != Mood.IDLE) try { Agent.stop() } catch (_: Throwable) { }
            askJob?.cancel(); askJob = null; askedAgent = false
            scope?.cancel(); scope = null
            recorder = null; lastHit = null; pendingHit = null
            val w = wm
            listOf(hand, hi, dock).forEach { v -> if (v != null) try { w?.removeViewImmediate(v) } catch (_: Exception) { } }
            hand = null; hi = null; dock = null; handLp = null; dockLp = null
            handOwner?.destroy(); handOwner = null
            dockOwner?.destroy(); dockOwner = null
            _ui.value = MagicHandState.close()
            carryingState.value = false; lit.value = false
            svc = null; wm = null
        }
    }

    // ---------- windows ----------
    private fun type() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun screen(): Pair<Int, Int> {
        val s = svc ?: return 1080 to 2340
        val w = wm
        return if (android.os.Build.VERSION.SDK_INT >= 30 && w != null) w.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else s.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun addHighlight(s: Context, w: WindowManager) {
        val v = HighlightView(s, density)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { w.addView(v, lp); hi = v } catch (_: Exception) { }
    }

    private fun addDock(s: Context, w: WindowManager) {
        val owner = OverlayOwner().also { it.start() }
        val calls = DockCalls(
            onButton = ::onDockButton, onMenu = ::onMenu, onConfirm = ::onConfirm, onGrab = ::onGrab,
            onBack = { set { MagicHandState.back(it) } },
            onTeachName = { n -> set { it.copy(teachName = n.take(60)) } },
            onTeachUndo = { recorder?.undo(); set { MagicHandState.teachSteps(it, teachLines()) } },
            onTeachSave = ::saveTeach,
            onTeachDiscard = { recorder?.clear(); recorder = null; set { MagicHandState.endTeach(it, "Discarded. Nothing was saved.") } },
        )
        val v = composeView(s, owner) {
            val u by ui.collectAsState()
            CompositionLocalProvider(LocalBlaze provides DarkColors) { MagicHandDock(u, calls) }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        try { w.addView(v, lp); dock = v; dockLp = lp; dockOwner = owner } catch (_: Exception) { owner.destroy() }
    }

    /** The teach review has a name field, so only then may the dock take the keyboard. */
    private fun updateDockFocus(focusable: Boolean) {
        val v = dock ?: return; val lp = dockLp ?: return
        val want = if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if ((lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == want) return
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or want
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private val tipX: Float get() { val box = (HAND_DP - 10) * density; return 5 * density + MascotArt.glyphTip(box).x }
    private val tipY: Float get() { val box = (HAND_DP - 10) * density; return 5 * density + MascotArt.glyphTip(box).y }

    @SuppressLint("ClickableViewAccessibility")
    private fun addHand(s: Context, w: WindowManager) {
        val size = (HAND_DP * density).toInt()
        val owner = OverlayOwner().also { it.start() }
        val frame = HandFrame(s)
        val inner = composeView(s, owner) { MagicHandGlyph(HAND_DP, lit.value, carryingState.value) }
        frame.addView(inner, FrameLayout.LayoutParams(size, size))
        frame.contentDescription = "Magic Hand. Drag to point, double-tap for a menu."
        val (sw, sh) = screen()
        val lp = WindowManager.LayoutParams(size, size, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = sw / 2 - size / 2; y = sh / 3 }
        val points = ArrayList<MagicHandLogic.P>()
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var lastTapUp = 0L; var lastProbe = 0L
        frame.onTouch = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    points.clear(); points += MagicHandLogic.P(lp.x + tipX, lp.y + tipY, e.eventTime)
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    try { w.updateViewLayout(frame, lp) } catch (_: Exception) { }
                    val p = MagicHandLogic.P(lp.x + tipX, lp.y + tipY, e.eventTime)
                    points += p
                    hi?.trail(points)
                    if (e.eventTime - lastProbe > 90) { lastProbe = e.eventTime; probe(p.x.toInt(), p.y.toInt()) }
                }
                MotionEvent.ACTION_UP -> {
                    points += MagicHandLogic.P(lp.x + tipX, lp.y + tipY, e.eventTime)
                    val g = MagicHandLogic.classify(points, density, lastTapUp)
                    lastTapUp = if (g == Gesture.TAP) e.eventTime else 0L
                    val pts = points.toList()
                    hi?.trail(emptyList())
                    onGesture(g, pts)
                }
                MotionEvent.ACTION_CANCEL -> hi?.trail(emptyList())
            }
            true
        }
        try { w.addView(frame, lp); hand = frame; handLp = lp; handOwner = owner } catch (_: Exception) { owner.destroy() }
    }

    private fun tip(): Pair<Int, Int> { val lp = handLp ?: return 0 to 0; return (lp.x + tipX).toInt() to (lp.y + tipY).toInt() }

    private fun appLabel(pkg: String): String = try {
        val pm = svc!!.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg.substringAfterLast('.') }

    /** What's under the tip: highlight it and say what it is. */
    private fun probe(x: Int, y: Int) {
        val s = svc ?: return
        val h = try { MagicHandScreen.at(s, x, y) } catch (_: Exception) { null }
        lastHit = h
        lit.value = h != null
        hi?.box(h?.box)
        if (h != null) set { MagicHandState.point(it, if (h.kind == MagicHandLogic.Kind.SECRET) "Password field" else h.element.label, h.kind, appLabel(h.pkg)) }
    }

    // ---------- gestures ----------
    private fun onGesture(g: Gesture, pts: List<MagicHandLogic.P>) {
        if (g == Gesture.NONE || g == Gesture.TAP) return
        val (x, y) = tip()
        probe(x, y)
        val h = lastHit
        val u0 = _ui.value
        set { it.copy(private = isPrivate()) }
        val u = _ui.value
        if (u0.panel == Panel.TEACH_REVIEW) return
        val kind = h?.kind ?: MagicHandLogic.Kind.EMPTY
        when (MagicHandLogic.intentFor(g, u.mode, u.carrying, kind)) {
            HandIntent.NONE, HandIntent.HIGHLIGHT -> Unit
            HandIntent.MENU -> if (h == null && u.private) set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }
                else set { MagicHandState.menu(it, h?.element?.label.orEmpty(), kind, h?.pkg?.let(::appLabel).orEmpty()) }
            HandIntent.DROP -> { pendingHit = h; set { MagicHandState.confirm(it, HandIntent.DROP, h?.element?.label.orEmpty().ifBlank { "this box" }) } }
            HandIntent.WATCH -> askWatch(h)
            HandIntent.GRAB -> grab(pts)
            HandIntent.TEACH_TAP -> { pendingHit = h; set { MagicHandState.confirm(it, HandIntent.TEACH_TAP, h?.element?.label.orEmpty()) } }
        }
    }

    private fun askWatch(h: MagicHandScreen.Hit?) {
        if (!MagicHandGuard.canWatch(isPrivate())) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        if (h == null) { set { MagicHandState.back(it, MagicHandState.NOTHING) }; return }
        pendingHit = h
        set { MagicHandState.confirm(it, HandIntent.WATCH, h.element.label) }
    }

    // ---------- dock ----------
    private fun onDockButton(b: DockButton) {
        when (b) {
            DockButton.CLOSE -> stop()
            DockButton.TEACH -> {
                if (!MagicHandGuard.canTeach(isPrivate())) { set { MagicHandState.back(it.copy(private = true), MagicHandGuard.PRIVATE_NOTE) }; return }
                recorder = MagicHandTeach.Recorder(svc?.packageName.orEmpty())
                svc?.currentPkg()?.takeIf { it.isNotBlank() }?.let { p -> recorder?.window(p, appLabel(p), "", SystemClock.uptimeMillis()) }
                set { MagicHandState.teachSteps(MagicHandState.teach(it), teachLines()) }
            }
            DockButton.DONE -> {
                val steps = recorder?.steps.orEmpty()
                if (steps.none { it.kind == MagicHandTeach.Kind.TAP || it.kind == MagicHandTeach.Kind.TYPE }) {
                    recorder = null; set { MagicHandState.endTeach(it, "Nothing to save: I didn't see any taps or typing.") }
                } else set { MagicHandState.review(it, teachLines(), MagicHandTeach.toWorkflow(steps, "", "").name) }
            }
            DockButton.DROP_CHAT -> dropInChat()
        }
    }

    private fun teachLines(): List<String> = recorder?.steps.orEmpty().map(MagicHandTeach::line)

    private fun saveTeach() {
        val rec = recorder ?: return
        if (isPrivate()) { recorder = null; set { MagicHandState.endTeach(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        val d = MagicHandTeach.toWorkflow(rec.steps, _ui.value.teachName, "")
        scope?.launch {
            val r = withContext(Dispatchers.IO) {
                try { com.blaze.assistant.agent.SkillPacks.saveWorkflow(d.name, d.trigger, d.steps, d.checks, d.tags, tested = false) }
                catch (e: Exception) { "Couldn't save the workflow: ${e.message}" }
            }
            recorder = null
            set { MagicHandState.endTeach(it, if (r.startsWith("Workflow")) "Saved \u201c${d.name}\u201d. Blaze will follow it with you once before trusting it." else r) }
        }
    }

    private fun text(h: MagicHandScreen.Hit?): String = h?.element?.let { it.text.ifBlank { it.desc } }.orEmpty()

    private fun onMenu(a: Action) {
        val ctx = svc ?: return
        val h = lastHit
        val priv = isPrivate()
        if (!MagicHandGuard.allowed(a, priv)) { set { MagicHandState.back(it.copy(private = true), MagicHandGuard.PRIVATE_NOTE) }; return }
        val t = text(h)
        when (a) {
            Action.ASK, Action.EXPLAIN, Action.DESCRIBE, Action.TRANSLATE, Action.REMIND -> ask(a, h)
            Action.COPY -> { PointerActions.copy(ctx, "Text", t); set { MagicHandState.back(it, "Copied.") } }
            Action.SHARE -> { set { MagicHandState.back(it) }; PointerActions.share(ctx, t) }
            Action.SEARCH -> { set { MagicHandState.back(it) }; open(MagicHandState.searchUrl(t)) }
            Action.OPEN_LINK -> { set { MagicHandState.back(it) }; open(h?.element?.url?.ifBlank { t } ?: t) }
            Action.COPY_LINK -> { PointerActions.copy(ctx, "Link", h?.element?.url?.ifBlank { t } ?: t); set { MagicHandState.back(it, "Link copied.") } }
            Action.SAVE_IMAGE -> saveImage(h)
            Action.CARRY -> if (t.isBlank()) set { MagicHandState.back(it, MagicHandState.NOTHING) } else set { MagicHandState.carry(it, t) }
            Action.WATCH -> askWatch(h)
            Action.DROP -> { pendingHit = h; set { MagicHandState.confirm(it, HandIntent.DROP, h?.element?.label.orEmpty().ifBlank { "this box" }) } }
            Action.DROP_IN_CHAT -> dropInChat()
        }
    }

    private fun open(url: String) {
        val s = svc ?: return
        val u = url.trim().let { if (it.startsWith("www.")) "https://$it" else it }
        if (!u.startsWith("http")) return
        try { s.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    /** Hides every Magic Hand window, waits a frame, runs [block], shows them again. */
    private suspend fun <T> hidden(block: suspend () -> T): T {
        val views = listOfNotNull(hand, hi, dock)
        views.forEach { it.visibility = View.INVISIBLE }
        delay(160)
        try { return block() } finally { views.forEach { it.visibility = View.VISIBLE } }
    }

    private suspend fun crop(b: MagicHandLogic.Box, around: Boolean): android.graphics.Bitmap? {
        val s = svc ?: return null
        if (!MagicHandGuard.canScreenshot(isPrivate())) return null
        val full = hidden { MagicHandScreen.screenshot(s) } ?: return null
        val (sw, sh) = full.width to full.height
        val box = if (around) MagicHandLogic.cropAround(b, sw, sh, (24 * density).toInt(), 1200) else b
        return MagicHandScreen.crop(full, box)
    }

    /** Point & ask: the element's words and a crop of the screen around it go to Blaze; the answer shows in the dock. */
    private fun ask(a: Action, h: MagicHandScreen.Hit?, custom: String? = null) {
        if (Agent.mood.value != Mood.IDLE) { set { MagicHandState.back(it, MagicHandState.BUSY) }; return }
        val app = h?.pkg?.let(::appLabel).orEmpty()
        val q = custom ?: MagicHandLogic.prompt(a, text(h), app)
        set { MagicHandState.asking(it, custom ?: text(h)) }
        askJob?.cancel()
        askJob = scope?.launch {
            val att = if (custom == null && h != null && a != Action.TRANSLATE && a != Action.REMIND) crop(h.box, around = true)?.let { bmp ->
                val url = withContext(Dispatchers.Default) { MagicHandScreen.dataUrl(bmp) }
                Attachment("magic-hand.jpg", listOf(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))), null)
            } else null
            if (!on || isPrivate()) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return@launch }
            val before = Agent.messages.value.lastOrNull()?.id ?: Long.MIN_VALUE
            askedAgent = true
            Agent.send(q, byVoice = false, attachments = listOfNotNull(att))
            val t0 = SystemClock.uptimeMillis()
            var seenBusy = false
            while (on && SystemClock.uptimeMillis() - t0 < 180_000) {
                delay(300)
                if (Agent.mood.value != Mood.IDLE) seenBusy = true
                val reply = Agent.messages.value.lastOrNull { it.id > before && (it.role == Role.BLAZE || it.role == Role.ERROR) }
                if (reply != null && reply.text.isNotBlank()) set { MagicHandState.answered(it, reply.text) }
                if ((seenBusy || SystemClock.uptimeMillis() - t0 > 3_000) && Agent.mood.value == Mood.IDLE) {
                    if (reply == null) set { MagicHandState.answered(it, "Blaze didn't answer. Open Blaze to see what happened.") }
                    break
                }
            }
            askedAgent = false
        }
    }

    private fun dropInChat() {
        val c = _ui.value.carried
        if (c.isBlank()) return
        if (!MagicHandGuard.allowed(Action.DROP_IN_CHAT, isPrivate())) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        set { it.copy(carried = "") }
        ask(Action.ASK, null, custom = "Here's something I carried over from another app:\n\n\u201c${c.take(4000)}\u201d")
    }

    private fun saveImage(h: MagicHandScreen.Hit?) {
        val s = svc ?: return
        if (h == null) return
        scope?.launch {
            val bmp = crop(h.box, around = false)
            val ok = bmp != null && withContext(Dispatchers.IO) { saveToPictures(s, bmp) }
            set { MagicHandState.back(it, if (ok) "Saved to Pictures/Blaze." else "Couldn't save that picture.") }
        }
    }

    private fun saveToPictures(ctx: Context, bmp: android.graphics.Bitmap): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT < 29) false else {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "blaze-hand-${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Blaze")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri != null && (ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } ?: false)
        }
    } catch (_: Exception) { false }

    // ---------- circle to grab ----------
    private fun grab(pts: List<MagicHandLogic.P>) {
        val s = svc ?: return
        if (!MagicHandGuard.canGrab(isPrivate())) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        val (sw, sh) = screen()
        val b = MagicHandLogic.loopBounds(pts, sw, sh, (8 * density).toInt()) ?: return
        hi?.box(b)
        set { it.copy(note = "Reading what you circled\u2026") }
        scope?.launch {
            val bmp = crop(b, around = false)
            val ocr = if (bmp != null) MagicHandScreen.ocr(bmp) else ""
            val text = ocr.ifBlank { try { MagicHandScreen.textIn(s, b) } catch (_: Exception) { "" } }
            val src = lastHit?.pkg?.let(::appLabel) ?: s.currentPkg().let(::appLabel)
            set { MagicHandState.grabbed(it.copy(note = ""), text, src) }
        }
    }

    private fun onGrab(g: GrabAction) {
        val s = svc ?: return
        val t = _ui.value.grabbed
        when (g) {
            GrabAction.COPY -> { PointerActions.copy(s, "Text", t); set { MagicHandState.back(it, "Copied.") } }
            GrabAction.TRANSLATE -> ask(Action.TRANSLATE, null, custom = MagicHandLogic.prompt(Action.TRANSLATE, t, _ui.value.grabSource))
            GrabAction.SEARCH -> { set { MagicHandState.back(it) }; open(MagicHandState.searchUrl(t)) }
            GrabAction.SAVE -> {
                if (isPrivate()) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
                scope?.launch {
                    val src = _ui.value.grabSource
                    val r = withContext(Dispatchers.IO) { try { com.blaze.assistant.agent.Memory.add((if (src.isNotBlank()) "Saved from $src: " else "Saved: ") + t.take(1500)) } catch (e: Exception) { "Couldn't save: ${e.message}" } }
                    set { MagicHandState.back(it, if (r.startsWith("Not saved") || r.startsWith("Couldn")) r else "Saved to memory.") }
                }
            }
            GrabAction.CARRY -> set { MagicHandState.carry(it, t) }
        }
    }

    // ---------- confirm ----------
    private fun onConfirm(yes: Boolean) {
        val c = _ui.value.confirm ?: return
        val h = pendingHit; pendingHit = null
        if (!yes || h == null) { set { MagicHandState.back(it, "Cancelled. Nothing was done.") }; return }
        when (c.intent) {
            HandIntent.DROP -> set { if (drop(h.node, it.carried)) MagicHandState.dropped(it) else MagicHandState.back(it, "That box didn't take the text. It's on the clipboard; long-press the box and Paste.") }
            HandIntent.WATCH -> {
                val s = svc ?: return
                val value = text(h)
                if (value.isBlank() || isPrivate()) { set { MagicHandState.back(it, MagicHandState.NOTHING) }; return }
                MagicHandWatches.add(s, MagicHandWatch.Watch(java.util.UUID.randomUUID().toString(), h.pkg, appLabel(h.pkg),
                    h.element.desc.ifBlank { h.element.viewId.substringAfterLast('/').replace('_', ' ') }.ifBlank { value }.take(60),
                    h.path, h.element.viewId, h.element.className, value, System.currentTimeMillis()))
                set { MagicHandState.back(it, "Watching \u201c${MagicHandLogic.preview(value)}\u201d. I'll tell you when it changes.") }
            }
            HandIntent.TEACH_TAP -> {
                val s = svc ?: return
                recorder?.click(h.pkg, appLabel(h.pkg), h.element.label, h.element.viewId, h.element.className, SystemClock.uptimeMillis())
                ignoreClicksUntil = SystemClock.uptimeMillis() + 800
                set { MagicHandState.teachSteps(MagicHandState.back(it), teachLines()) }
                scope?.launch { try { s.tapNode(h.node) } catch (_: Exception) { } }
            }
            else -> set { MagicHandState.back(it) }
        }
    }

    /** Puts [carried] into an editable node (only after the user's yes): SET_TEXT, else the clipboard and Paste. */
    private fun drop(node: AccessibilityNodeInfo, carried: String): Boolean {
        if (carried.isBlank() || node.isPassword) return false
        try { node.refresh() } catch (_: Exception) { }
        val hint = android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val existing = if (hint) "" else node.text?.toString().orEmpty()
        val next = if (existing.isBlank()) carried else existing.trimEnd() + " " + carried
        val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }) } catch (_: Exception) { false }
        if (ok) return true
        val s = svc ?: return false
        return try {
            s.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Blaze", carried))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) { false }
    }

    // ---------- accessibility events ----------
    /** From BlazeAccessibilityService: watches re-read when their app comes to the front; teach records the user's own steps. */
    fun onEvent(s: BlazeAccessibilityService, e: AccessibilityEvent) {
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == s.packageName) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            main.removeCallbacks(watchCheck)
            watchPkg = pkg
            main.postDelayed(watchCheck, 1500)
        }
        val rec = recorder ?: return
        if (!on || _ui.value.mode != MagicHandLogic.Mode.TEACH || _ui.value.panel == Panel.TEACH_REVIEW) return
        if (isPrivate()) { recorder = null; set { MagicHandState.endTeach(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        val now = SystemClock.uptimeMillis()
        val app = appLabel(pkg)
        val src = try { e.source } catch (_: Exception) { null }
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val screen = e.text?.firstOrNull()?.toString()?.trim().orEmpty().ifBlank { e.className?.toString()?.substringAfterLast('.').orEmpty() }
                rec.window(pkg, app, screen.take(60), now)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (now < ignoreClicksUntil) return
                val label = e.text?.joinToString(" ")?.trim().orEmpty().ifBlank { e.contentDescription?.toString().orEmpty() }
                    .ifBlank { src?.let { MagicHandScreen.elementOf(it).label }.orEmpty() }
                rec.click(pkg, app, label, src?.viewIdResourceName.orEmpty(), e.className?.toString().orEmpty(), now)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val field = (if (android.os.Build.VERSION.SDK_INT >= 26) src?.hintText?.toString() else null).orEmpty()
                    .ifBlank { src?.contentDescription?.toString().orEmpty() }
                rec.typed(pkg, app, field, src?.viewIdResourceName.orEmpty(), e.text?.joinToString("")?.toString().orEmpty(), e.isPassword || src?.isPassword == true, now)
            }
            else -> return
        }
        set { MagicHandState.teachSteps(it, teachLines()) }
    }

    private var watchPkg = ""
    private val watchCheck = Runnable {
        val s = BlazeAccessibilityService.instance ?: return@Runnable
        try { if (watchPkg.isNotBlank() && s.currentPkg() == watchPkg) MagicHandWatches.check(s, watchPkg) } catch (_: Exception) { }
    }

    // ---------- views ----------
    /** The hand's window: every touch goes to [onTouch] (the Compose glyph inside only draws). */
    private class HandFrame(ctx: Context) : FrameLayout(ctx) {
        var onTouch: (MotionEvent) -> Boolean = { false }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = onTouch(ev)
    }

    /** Full-screen, not touchable: a rounded outline around what's under the tip and the trail of a circle being drawn. */
    private class HighlightView(ctx: Context, private val d: Float) : View(ctx) {
        private var b: MagicHandLogic.Box? = null
        private val path = Path()
        private var trailOn = false
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFFF9A3C.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x22FF9A3C }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xCC4C7DFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun box(n: MagicHandLogic.Box?) { if (n != b) { b = n; invalidate() } }
        fun trail(pts: List<MagicHandLogic.P>) {
            path.reset(); trailOn = pts.size > 2
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            c.save(); c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            b?.let { r -> val rf = RectF(r.l - 3 * d, r.t - 3 * d, r.r + 3 * d, r.b + 3 * d); c.drawRoundRect(rf, 12 * d, 12 * d, fill); c.drawRoundRect(rf, 12 * d, 12 * d, stroke) }
            if (trailOn) c.drawPath(path, trail)
            c.restore()
        }
    }
}
