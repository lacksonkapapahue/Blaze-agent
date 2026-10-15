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

    /** A shell a tab can run. [hint] = the package-manager lines in the welcome text. */
    data class Env(val id: String, val label: String, val short: String, val hint: List<String> = emptyList())

    /** A Linux the user can install. [urls] maps CPU ("aarch64"/"arm") to a proot-distro rootfs tarball (.tar.xz). */
    data class Distro(val id: String, val name: String, val version: String, val sizeMb: Map<String, Int>,
                      val urls: Map<String, String>, val hint: List<String>) {
        fun env() = Env(id, "$name $version".trim(), id, hint)
    }

    private const val PD = "https://github.com/termux/proot-distro/releases/download"

    val DISTROS: List<Distro> = listOf(
        Distro("alpine", "Alpine Linux", "", mapOf("aarch64" to 4, "arm" to 4), emptyMap(),
            listOf("Search:  apk search <query>", "Install: apk add <package>", "Upgrade: apk update && apk upgrade")),
        Distro("ubuntu", "Ubuntu", "24.04", mapOf("aarch64" to 64, "arm" to 63),
            mapOf("aarch64" to "$PD/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz", "arm" to "$PD/v4.18.0/ubuntu-noble-arm-pd-v4.18.0.tar.xz"),
            listOf("Search:  apt search <query>", "Install: apt install <package>", "Upgrade: apt update && apt upgrade")),
        Distro("debian", "Debian", "12", mapOf("aarch64" to 43, "arm" to 42),
            mapOf("aarch64" to "$PD/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz", "arm" to "$PD/v4.17.3/debian-bookworm-arm-pd-v4.17.3.tar.xz"),
            listOf("Search:  apt search <query>", "Install: apt install <package>", "Upgrade: apt update && apt upgrade")),
        Distro("archlinux", "Arch Linux", "ARM", mapOf("aarch64" to 150, "arm" to 141),
            mapOf("aarch64" to "$PD/v4.22.1/archlinux-aarch64-pd-v4.22.1.tar.xz", "arm" to "$PD/v4.22.1/archlinux-arm-pd-v4.22.1.tar.xz"),
            listOf("Search:  pacman -Ss <query>", "Install: pacman -S <package>", "Upgrade: pacman -Syu")),
        Distro("fedora", "Fedora", "", mapOf("aarch64" to 34), mapOf("aarch64" to "$PD/v4.24.0/fedora-aarch64-pd-v4.24.0.tar.xz"),
            listOf("Search:  dnf search <query>", "Install: dnf install <package>", "Upgrade: dnf upgrade")),
        Distro("manjaro", "Manjaro", "ARM", mapOf("aarch64" to 141), mapOf("aarch64" to "$PD/v4.18.0/manjaro-aarch64-pd-v4.18.0.tar.xz"),
            listOf("Search:  pacman -Ss <query>", "Install: pacman -S <package>", "Upgrade: pacman -Syu")),
    )

    fun distro(id: String): Distro? = DISTROS.firstOrNull { it.id == id }

    /** "aarch64" / "arm" for the phone's first ABI, null when no rootfs is built for it. */
    fun cpu(abi: String?): String? = when (abi) { "arm64-v8a" -> "aarch64"; "armeabi-v7a", "armeabi" -> "arm"; else -> null }

    /** The distros this phone can install (Alpine has its own installer and works everywhere Linux does). */
    fun installable(abi: String?): List<Distro> = DISTROS.filter { it.id == "alpine" || cpu(abi)?.let { c -> it.urls[c] } != null }

    val PHONE = Env("phone", "Phone shell (BusyBox)", "phone")
    val ALPINE = DISTROS.first().env()

    /** What a new tab can run: the installed distros (in list order), then the phone shell. */
    fun envs(installedIds: Set<String>): List<Env> = DISTROS.filter { it.id in installedIds }.map { it.env() } + PHONE

    /** The default for a new tab: the last one used if it's still there, else the first available. */
    fun pick(last: String?, installedIds: Set<String>): Env = envs(installedIds).let { l -> l.firstOrNull { it.id == last } ?: l.first() }

    /** pax extended header records: "<len> key=value\n" … */
    fun pax(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        val bytes = body.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            var sp = i; while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i).trim().toIntOrNull() ?: break
            if (len <= 0 || i + len > bytes.size) break
            val rec = String(bytes, sp + 1, i + len - sp - 1, Charsets.UTF_8).trimEnd('\n')
            val eq = rec.indexOf('=')
            if (eq > 0) out += rec.substring(0, eq) to rec.substring(eq + 1)
            i += len
        }
        return out
    }

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
        if (env.id != "phone") {
            append(" ${env.label}, running on this phone.\r\n\r\n Working with packages:\r\n\r\n")
            for (h in env.hint) append("  - $h\r\n")
            append("\r\n Your Blaze files are in ~/phone, storage in /sdcard.\r\n")
        } else {
            append(" Phone shell with BusyBox tools.\r\n Install a Linux (Ubuntu, Debian, Arch, Alpine…) from the + menu.\r\n")
        }
        append(" Pinch to zoom · long-press to copy · ⋮ to paste.\r\n\r\n")
    }
}
