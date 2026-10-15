package com.blaze.assistant.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.29.2 Blaze Terminal: a full-screen, Termux-style terminal. Black screen, monospace text, Termux's two
 * extra-keys rows (ESC / ― HOME ↑ END PGUP · ⇥ CTRL ALT ← ↓ → PGDN), tabs along the top, pinch to zoom,
 * long-press to select and copy, paste from the menu. Tabs run the user's Alpine Linux (PRoot) or the phone shell.
 */
class BlazeTerminalActivity : Activity(), TermSessions.Listener {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, BlazeTerminalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        private var fontPx = 0
    }

    private lateinit var term: TerminalView
    private lateinit var tabRow: LinearLayout
    private val keyViews = mutableMapOf<String, TextView>()
    private var ctrl = false
    private var alt = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val ink = Color.parseColor("#E6E6E6")
    private val dim = Color.parseColor("#8A8A8A")
    private val accent = Color.parseColor("#FF8A3D")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        if (fontPx == 0) fontPx = (resources.displayMetrics.scaledDensity * 13).toInt().coerceIn(TermLogic.MIN_FONT, TermLogic.MAX_FONT)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // Tabs + menu along the top.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(2)) }
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("+", false) { newTabMenu() })
        top.addView(chip("⋮", false) { moreMenu() })
        root.addView(top)

        term = TerminalView(this, null).apply {
            setBackgroundColor(Color.BLACK)
            setTextSize(fontPx)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true; isFocusableInTouchMode = true
            keepScreenOn = true
        }
        term.setTerminalViewClient(viewClient)
        root.addView(term, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        for (row in TermLogic.ROWS) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in row) r.addView(extraKey(k), LinearLayout.LayoutParams(0, dp(42), 1f))
            root.addView(r)
        }
        setContentView(root)

        TermSessions.screen = this
        val tab = TermSessions.ensureOne(this)
        attach(tab)
        structure()
        term.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        TermSessions.screen = this
        TermSessions.active()?.let { attach(it) }
        structure()
    }

    override fun onDestroy() {
        if (TermSessions.screen === this) TermSessions.screen = null
        super.onDestroy()
    }

    private fun attach(tab: TermSessions.Tab) {
        term.attachSession(tab.session)
        term.onScreenUpdated()
    }

    // ---- TermSessions.Listener ----
    override fun changed(tab: TermSessions.Tab?) {
        if (tab == null || tab === TermSessions.active()) term.onScreenUpdated()
    }

    override fun structure() {
        tabRow.removeAllViews()
        val tabs = synchronized(TermSessions.tabs) { TermSessions.tabs.toList() }
        tabs.forEachIndexed { i, t ->
            val sel = i == TermSessions.current
            val c = chip(TermLogic.tabLabel(t.n, t.env, t.session.title, t.finished), sel) {
                if (TermSessions.current != i) { TermSessions.select(i); attach(t) }
            }
            c.setOnLongClickListener { confirmClose(t); true }
            tabRow.addView(c)
        }
        TermSessions.active()?.let { if (term.currentSession !== it.session) attach(it) }
    }

    // ---- UI pieces ----
    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE; textSize = 13f
        setTextColor(if (selected) Color.BLACK else ink)
        background = pill(if (selected) accent else Color.parseColor("#161616"), if (selected) accent else Color.parseColor("#2A2A2A"))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun extraKey(k: TermLogic.Key) = TextView(this).apply {
        text = k.label; gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE; textSize = 15f
        setTextColor(ink); setBackgroundColor(Color.BLACK)
        if (k.toggle.isNotEmpty()) keyViews[k.toggle] = this
        var repeating: Runnable? = null
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.setBackgroundColor(Color.parseColor("#2A2A2A"))
                    press(k)
                    if (k.repeat) {
                        val r = object : Runnable { override fun run() { press(k); main.postDelayed(this, 60) } }
                        repeating = r; main.postDelayed(r, 400)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.setBackgroundColor(Color.BLACK)
                    repeating?.let { main.removeCallbacks(it) }; repeating = null
                }
            }
            true
        }
    }

    private fun press(k: TermLogic.Key) {
        val s = term.currentSession ?: return
        when {
            k.toggle == "ctrl" -> { ctrl = !ctrl; paintToggles() }
            k.toggle == "alt" -> { alt = !alt; paintToggles() }
            k.code != 0 -> {
                var mod = 0
                if (ctrl) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_CTRL
                if (alt) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_ALT
                term.handleKeyCode(k.code, mod)
                if (!k.repeat) resetToggles()
            }
            k.text.isNotEmpty() -> {
                if (ctrl || alt) { term.inputCodePoint(k.text.codePointAt(0), ctrl, alt); resetToggles() }
                else s.write(k.text)
            }
        }
    }

    private fun paintToggles() {
        keyViews["ctrl"]?.setTextColor(if (ctrl) accent else ink)
        keyViews["alt"]?.setTextColor(if (alt) accent else ink)
    }

    private fun resetToggles() { if (ctrl || alt) { ctrl = false; alt = false; paintToggles() } }

    private fun showKeyboard() {
        term.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(term, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun newTabMenu() {
        val envs = TermSessions.envs()
        val labels = envs.map { it.label } + "Install a Linux…"
        AlertDialog.Builder(this).setTitle("New tab").setItems(labels.toTypedArray()) { _, i ->
            if (i < envs.size) { attach(TermSessions.open(this, envs[i])); showKeyboard() } else installMenu()
        }.show()
    }

    /** Every distro this phone can run, with its size; installed ones can be removed (long-press style: second dialog). */
    private fun installMenu() {
        if (!Linux.supported()) { Toast.makeText(this, "Linux isn't supported on this phone.", Toast.LENGTH_LONG).show(); return }
        val cpu = TermLogic.cpu(android.os.Build.SUPPORTED_ABIS.firstOrNull()) ?: "aarch64"
        val list = Distros.installable()
        val labels = list.map { d ->
            val size = d.sizeMb[cpu]?.let { "~$it MB" } ?: ""
            "${d.env().label}  ·  " + if (Distros.installed(d.id)) "installed" else size
        }
        AlertDialog.Builder(this).setTitle("Linux on this phone").setItems(labels.toTypedArray()) { _, i ->
            val d = list[i]
            if (Distros.installed(d.id)) {
                AlertDialog.Builder(this).setTitle(d.env().label)
                    .setItems(arrayOf("Open a new tab", "Remove ${d.name}")) { _, j ->
                        if (j == 0) { attach(TermSessions.open(this, d.env())); showKeyboard() }
                        else AlertDialog.Builder(this).setMessage("Remove ${d.name} and everything installed in it?")
                            .setPositiveButton("Remove") { _, _ ->
                                synchronized(TermSessions.tabs) { TermSessions.tabs.filter { it.env.id == d.id } }.forEach { TermSessions.close(it) }
                                scope.launch { withContext(Dispatchers.IO) { Distros.uninstall(d.id) }; Toast.makeText(this@BlazeTerminalActivity, "${d.name} removed", Toast.LENGTH_SHORT).show()
                                    TermSessions.active()?.let { attach(it) } ?: attach(TermSessions.open(this@BlazeTerminalActivity)) }
                            }.setNegativeButton("Cancel", null).show()
                    }.show()
            } else install(d)
        }.show()
    }

    private fun install(d: TermLogic.Distro) {
        val text = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); textSize = 14f; this.text = "Starting…" }
        val dlg = AlertDialog.Builder(this).setTitle("Installing ${d.env().label}").setView(text)
            .setNegativeButton("Hide", null).setCancelable(true).show()
        val watch = scope.launch {
            val flow = if (d.id == "alpine") Linux.status else Distros.status
            flow.collect { text.text = it.ifBlank { "Starting…" } }
        }
        scope.launch {
            val r = Distros.install(d.id)
            watch.cancel()
            if (Distros.installed(d.id)) {
                dlg.dismiss()
                Toast.makeText(this@BlazeTerminalActivity, r.take(160), Toast.LENGTH_SHORT).show()
                attach(TermSessions.open(this@BlazeTerminalActivity, d.env())); showKeyboard()
            } else text.text = r
        }
    }

    private fun moreMenu() {
        val tab = TermSessions.active()
        val items = listOf("Paste", "Copy all text", "Keyboard", "Bigger text", "Smaller text", "Restart this tab", "Close this tab")
        AlertDialog.Builder(this).setItems(items.toTypedArray()) { _, i ->
            val s = term.currentSession
            when (i) {
                0 -> s?.let { pasteInto(it) }
                1 -> s?.emulator?.screen?.transcriptText?.let { txt ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Blaze Terminal", txt)); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                2 -> showKeyboard()
                3 -> setFont(TermLogic.zoom(fontPx, 1.15f))
                4 -> setFont(TermLogic.zoom(fontPx, 1 / 1.15f))
                5 -> tab?.let { val env = it.env; TermSessions.close(it); attach(TermSessions.open(this, env)) }
                6 -> tab?.let { confirmClose(it) }
            }
        }.show()
    }

    private fun pasteInto(s: TerminalSession) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        s.emulator?.paste(t)
    }

    private fun setFont(px: Int) { fontPx = px; term.setTextSize(px) }

    private fun confirmClose(t: TermSessions.Tab) {
        if (t.finished) { closeTab(t); return }
        AlertDialog.Builder(this).setMessage("Close tab ${t.n}? Anything running in it stops.")
            .setPositiveButton("Close") { _, _ -> closeTab(t) }.setNegativeButton("Cancel", null).show()
    }

    private fun closeTab(t: TermSessions.Tab) {
        TermSessions.close(t)
        val next = TermSessions.active()
        if (next == null) finish() else attach(next)
    }

    // ---- Termux's view client ----
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) { setFont(TermLogic.zoom(fontPx, scale)); return 1.0f }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            val t = TermSessions.active()
            if (t != null && t.finished) { val env = t.env; TermSessions.close(t); attach(TermSessions.open(this@BlazeTerminalActivity, env)) }
            showKeyboard()
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) { }
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_ENTER && TermSessions.active()?.finished == true) {
                TermSessions.active()?.let { val env = it.env; TermSessions.close(it); attach(TermSessions.open(this@BlazeTerminalActivity, env)) }
                return true
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrl
        override fun readAltKey() = alt
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (ctrl || alt) main.post { resetToggles() }
            return false
        }
        override fun onEmulatorSet() {
            val t = TermSessions.active() ?: return
            if (!t.welcomed) {
                t.welcomed = true
                val b = TermLogic.welcome(t.env).toByteArray()
                t.session.emulator?.append(b, b.size)
                term.onScreenUpdated()
            }
        }
        override fun logError(tag: String?, message: String?) { }
        override fun logWarn(tag: String?, message: String?) { }
        override fun logInfo(tag: String?, message: String?) { }
        override fun logDebug(tag: String?, message: String?) { }
        override fun logVerbose(tag: String?, message: String?) { }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { }
        override fun logStackTrace(tag: String?, e: Exception?) { }
    }
}
