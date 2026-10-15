package com.blaze.assistant.terminal

import android.view.KeyEvent

/**
 * v0.29.2 Blaze Terminal: pure parts (TermLogicTest). The extra-keys rows copy Termux's default layout, the
 * environment list decides which shells a new tab can open, and [phoneArgs] builds the phone shell's argv.
 */
object TermLogic {
    /** A key in the extra-keys rows: [code] is an Android key code, [text] is typed as-is, [toggle] is CTRL/ALT. */
    data class Key(val label: String, val code: Int = 0, val text: String = "", val toggle: String = "", val repeat: Boolean = false)

    val ROWS: List<List<Key>> = listOf(
        listOf(Key("ESC", KeyEvent.KEYCODE_ESCAPE), Key("/", text = "/"), Key("―", text = "-"), Key("HOME", KeyEvent.KEYCODE_MOVE_HOME),
            Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true), Key("END", KeyEvent.KEYCODE_MOVE_END), Key("PGUP", KeyEvent.KEYCODE_PAGE_UP, repeat = true)),
        listOf(Key("⇥", KeyEvent.KEYCODE_TAB), Key("CTRL", toggle = "ctrl"), Key("ALT", toggle = "alt"), Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
            Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true), Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true), Key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true)),
    )

    data class Env(val id: String, val label: String, val short: String)

    val ALPINE = Env("alpine", "Alpine Linux", "alpine")
    val PHONE = Env("phone", "Phone shell (BusyBox)", "phone")

    /** What a new tab can run: the installed Linux first (it's the full one), then the phone shell. */
    fun envs(alpineInstalled: Boolean): List<Env> = if (alpineInstalled) listOf(ALPINE, PHONE) else listOf(PHONE)

    /** The default for a new tab: the last one used if it's still there, else the first available. */
    fun pick(last: String?, alpineInstalled: Boolean): Env = envs(alpineInstalled).let { l -> l.firstOrNull { it.id == last } ?: l.first() }

    /** argv for the phone shell: BusyBox ash (line editing, history) when BusyBox ships, else Android's sh. */
    fun phoneArgs(busybox: String?): Pair<String, List<String>> =
        if (busybox != null) busybox to listOf("ash", "-l") else "/system/bin/sh" to listOf("sh", "-l")

    /** "KEY=value" pairs for the PTY (the native side clears the environment first). */
    fun envArray(vararg maps: Map<String, String>): Array<String> {
        val m = linkedMapOf<String, String>()
        for (x in maps) m.putAll(x)
        m["TERM"] = "xterm-256color"; m["COLORTERM"] = "truecolor"
        return m.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /** Tab chip text: "1 alpine", or the program's title when it set one ("1 vim"). */
    fun tabLabel(n: Int, env: Env, title: String?, finished: Boolean): String {
        val t = title?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 } ?: env.short
        return "$n $t" + if (finished) " ✕" else ""
    }

    const val MIN_FONT = 14
    const val MAX_FONT = 64

    /** Pinch zoom: the new text size in px, kept in range. */
    fun zoom(current: Int, scale: Float): Int = (current * scale).toInt().coerceIn(MIN_FONT, MAX_FONT)

    /** The first lines of a new tab, Termux-style. */
    fun welcome(env: Env): String = buildString {
        append("\r\n Welcome to Blaze Terminal!\r\n\r\n")
        if (env.id == "alpine") {
            append(" Alpine Linux, running on this phone.\r\n\r\n Working with packages:\r\n\r\n")
            append("  - Search:  apk search <query>\r\n  - Install: apk add <package>\r\n  - Upgrade: apk update && apk upgrade\r\n\r\n")
            append(" Your phone's Blaze files are in ~/phone, storage in /sdcard.\r\n")
        } else {
            append(" Phone shell with BusyBox tools.\r\n Install Alpine Linux from the + menu for apk packages.\r\n")
        }
        append(" Pinch to zoom · long-press to copy or paste.\r\n\r\n")
    }
}
