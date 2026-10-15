package com.blaze.assistant.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * v0.29.2: the terminal's tabs live here, outside the screen, so shells keep running when you leave the
 * Terminal view and come back (like Termux). Each tab is a real PTY (Termux's terminal-emulator library):
 * vim, htop, nano, colours, Ctrl-C and resizing all work.
 */
object TermSessions {
    class Tab(val n: Int, val env: TermLogic.Env, val session: TerminalSession) {
        @Volatile var finished = false
        @Volatile var welcomed = false
    }

    val tabs = mutableListOf<Tab>()
    @Volatile var current = 0
    private var counter = 0
    private var lastEnv: String? = null

    /** The open Terminal screen, told about output, titles and exits. */
    var screen: Listener? = null

    interface Listener {
        fun changed(tab: Tab?)
        fun structure()
    }

    private lateinit var app: Context

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { screen?.changed(find(s)) }
        override fun onTitleChanged(s: TerminalSession) { screen?.structure() }
        override fun onSessionFinished(s: TerminalSession) { find(s)?.finished = true; screen?.structure(); screen?.changed(find(s)) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Blaze Terminal", text.orEmpty()))
        }
        override fun onPasteTextFromClipboard(s: TerminalSession?) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString() ?: return
            s?.emulator?.paste(t)
        }
        override fun onBell(s: TerminalSession) { }
        override fun onColorsChanged(s: TerminalSession) { screen?.changed(find(s)) }
        override fun onTerminalCursorStateChange(state: Boolean) { }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { }
        override fun logWarn(tag: String?, message: String?) { }
        override fun logInfo(tag: String?, message: String?) { }
        override fun logDebug(tag: String?, message: String?) { }
        override fun logVerbose(tag: String?, message: String?) { }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { }
        override fun logStackTrace(tag: String?, e: Exception?) { }
    }

    fun find(s: TerminalSession): Tab? = synchronized(tabs) { tabs.firstOrNull { it.session === s } }
    fun active(): Tab? = synchronized(tabs) { tabs.getOrNull(current) }

    fun envs(): List<TermLogic.Env> = TermLogic.envs(Linux.installed())

    /** A new tab running [env] (or the default one). */
    fun open(ctx: Context, env: TermLogic.Env? = null): Tab {
        app = ctx.applicationContext
        Terminal.init(app); Terminal.setup()
        val e = env ?: TermLogic.pick(lastEnv, Linux.installed())
        lastEnv = e.id
        val home = Terminal.home().absolutePath
        val session = if (e.id == "alpine" && Linux.installed()) {
            val cmd = Linux.command(null, "xterm-256color")
            TerminalSession(cmd.first(), home, cmd.toTypedArray(), TermLogic.envArray(Terminal.env(), Linux.env()), 3000, client)
        } else {
            val bb = File(app.applicationInfo.nativeLibraryDir, "libbusybox.so").takeIf { it.exists() }?.absolutePath
            val (path, argv) = TermLogic.phoneArgs(bb)
            TerminalSession(path, home, argv.toTypedArray(), TermLogic.envArray(Terminal.env(), mapOf("PS1" to "\\w \\$ ")), 3000, client)
        }
        val tab = Tab(++counter, e, session)
        synchronized(tabs) { tabs += tab; current = tabs.size - 1 }
        screen?.structure()
        return tab
    }

    fun select(i: Int) { synchronized(tabs) { if (i in tabs.indices) current = i }; screen?.structure() }

    fun close(tab: Tab) {
        tab.session.finishIfRunning()
        synchronized(tabs) {
            val i = tabs.indexOf(tab); tabs.remove(tab)
            if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0) else if (i in 0 until current) current--
        }
        screen?.structure()
    }

    fun ensureOne(ctx: Context): Tab = active() ?: open(ctx)
}
