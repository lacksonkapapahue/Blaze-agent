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

    /** v0.30.1: the "phone" tab, renamed so it says what it is: this phone's own command line, nothing to install. */
    val PHONE = Env("phone", "Phone shell · built-in tools, no install", "phone sh")
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

    /**
     * v0.30.1: how the phone tab starts. BusyBox ash only when BusyBox is there AND passed the start-up probe
     * ([probeOk]); otherwise Android's own /system/bin/sh (mksh + toybox: ls, top, ping, ps… still work), so the tab
     * never opens as "[Process completed (signal 31)]". [busybox] is the path of libbusybox.so (argv[0] "ash" picks the applet).
     */
    data class Launch(val path: String, val argv: List<String>, val busybox: Boolean)

    fun phoneLaunch(busybox: String?, busyboxWorks: Boolean): Launch =
        if (busybox != null && busyboxWorks) Launch(busybox, listOf("ash", "-l"), true)
        else Launch("/system/bin/sh", listOf("sh", "-l"), false)

    /** The BusyBox start-up probe (`busybox sh -c 'echo ok'`) passed: exit 0 and "ok". A seccomp kill exits 128+31 = 159. */
    fun probeOk(exitCode: Int, output: String): Boolean = exitCode == 0 && output.trim().lines().lastOrNull()?.trim() == "ok"

    const val SIGSYS = 31

    /** Termux's TerminalSession exit status: >= 0 an exit code, < 0 minus the signal that killed it. */
    fun signalOf(exitStatus: Int): Int? = if (exitStatus < 0) -exitStatus else null

    /**
     * v0.30.1: the extra line under "[Process completed …]" when a phone tab dies at once. Signal 31 (SIGSYS) means
     * Android's app sandbox (seccomp) killed it for a blocked system call; the next start uses Android's own shell.
     */
    fun finishNote(envId: String, usedBusybox: Boolean, exitStatus: Int): String? {
        if (envId != "phone") return null
        return when (signalOf(exitStatus)) {
            SIGSYS -> if (usedBusybox) "\r\n Android's app sandbox stopped BusyBox (signal 31 = a blocked system call).\r\n Press Enter: this tab reopens with Android's own shell instead.\r\n"
                      else "\r\n Android's app sandbox stopped the shell (signal 31). Press Enter to try again.\r\n"
            null -> null
            else -> "\r\n The shell stopped. Press Enter to open a fresh one.\r\n"
        }
    }

    /** BusyBox applets not linked onto PATH: Android's own versions work for apps (ping uses Android's ICMP sockets) or need root anyway. */
    val KEEP_ANDROIDS = setOf("sh", "su", "reboot", "init", "mount", "umount", "ping", "ping6", "login", "passwd")

    /** Tappable example commands above the extra keys in a phone tab: (label, what it types). Each runs on tap. */
    val PHONE_EXAMPLES: List<Pair<String, String>> = listOf(
        "help" to "help", "ls" to "ls -l", "cd storage" to "cd /sdcard && ls", "cd home" to "cd ~ && pwd",
        "top" to "top", "ping" to "ping -c 3 google.com", "space" to "df -h", "phone info" to "uname -a; getprop ro.product.model",
        "clear" to "clear",
    )

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

    /** The first lines of a new tab, Termux-style. Lines stay under ~44 columns so a small phone doesn't wrap them. */
    fun welcome(env: Env, busybox: Boolean = true): String = buildString {
        append("\r\n Welcome to Blaze Terminal!\r\n\r\n")
        if (env.id != "phone") {
            append(" ${env.label}, running on this phone.\r\n\r\n Working with packages:\r\n\r\n")
            for (h in env.hint) append("  - $h\r\n")
            append("\r\n Your Blaze files are in ~/phone, storage in /sdcard.\r\n")
            append(" Pinch to zoom · long-press to copy · ⋮ to paste.\r\n\r\n")
        } else {
            append(" This is the PHONE SHELL: a command line\r\n")
            append(" for this phone, using the tools built\r\n")
            append(if (busybox) " into Android and Blaze (BusyBox).\r\n" else " into Android (toybox).\r\n")
            append(" Nothing to install.\r\n\r\n")
            append(" Not a full Linux: no apt, python or git.\r\n")
            append(" For those: + → Install a Linux…\r\n\r\n")
            append(" Try these, or tap the buttons below:\r\n")
            for ((cmd, what) in PHONE_TOUR) append("   ").append(cmd.padEnd(11)).append(what).append("\r\n")
            append("\r\n Type a command and press Enter.\r\n Ctrl-C stops a running command.\r\n\r\n")
        }
    }

    /** The welcome text's short tour of the phone shell: command → what it does. */
    val PHONE_TOUR: List<Pair<String, String>> = listOf(
        "ls" to "list the files here",
        "cd /sdcard" to "your phone's storage",
        "cd ~" to "back to the home folder",
        "top" to "what's running (q quits)",
        "df -h" to "free storage space",
        "help" to "every tool you can use",
    )

    /**
     * v0.30.1: the phone shell's start-up file (sourced through \$ENV by BusyBox ash and Android's mksh): a `help`
     * command that explains the shell and lists its tools, plus a short prompt. [busybox] = BusyBox ash is the shell.
     */
    fun phoneRc(busybox: Boolean): String = buildString {
        append("# Blaze Terminal phone shell (written by Blaze on each start; put your own settings in ~/.profile)\n")
        append(if (busybox) "PS1='phone:\\w \\$ '\n" else "PS1='phone:\${PWD##*/} \$ '\n")
        append("help() {\n  cat <<'BLAZE_HELP'\n")
        append(HELP_TEXT)
        append(if (busybox) "\nEvery tool here:   busybox --list   (or: ls /system/bin)\n" else "\nEvery tool here:   ls /system/bin\n")
        append("A tool's options:  <tool> --help    e.g.  ls --help\nBLAZE_HELP\n}\n")
    }

    val HELP_TEXT = """
Blaze phone shell: commands for this phone itself.
Type one and press Enter. Ctrl-C stops a command.

Files and folders
  ls                list files (ls -l for details)
  cd <folder>       go into a folder (cd .. = up)
  pwd               where am I?
  cd /sdcard        your photos, downloads, documents
  cat <file>        show a file
  cp / mv / rm      copy, move, delete
  mkdir <name>      make a folder
  vi <file>         edit a text file (Esc :wq saves)
  find . -name '*.jpg'   search for files

This phone
  top               running programs (q quits)
  df -h             free storage
  free -m           memory
  uname -a          Android's kernel
  getprop ro.product.model   phone model

Network
  ping -c 3 google.com   is the internet working?
  wget <url>        download a file
  ip addr           this phone's addresses

Want apt, python, node or git?
  Tap + at the top, then "Install a Linux…".
""".trimStart('\n')
}
