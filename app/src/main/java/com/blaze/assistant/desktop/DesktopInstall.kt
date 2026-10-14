package com.blaze.assistant.desktop

import com.blaze.assistant.terminal.AlpineNet

/**
 * v0.25.5: the desktop install that keeps going (DesktopInstallTest). v0.25.1 ran `apk update; apk add <everything>`
 * once under `set -e`, so one dropped connection anywhere in ~260 MB failed the whole install and the next Start
 * began from the top. Now each step (refresh the package list, then each [DesktopPlan.BATCHES] batch) is retried
 * with backoff, moves to another official mirror after two failures on one, refreshes the list again after any
 * failure (a mirror can change mid-install), and stops straight away only when the phone is out of space. Pure: the
 * shell runner, the status line and the sleep are passed in.
 */
object DesktopInstall {
    const val TRIES = 5

    enum class Fail { NETWORK, SPACE, MISSING, OTHER }

    private val network = Regex("(?i)temporary error|network error|dns lookup|could not connect|connection (refused|reset|timed out|closed)|" +
        "timed? ?out|i/o error|\\bio error|unable to fetch|network is unreachable|no route to host|\\btls\\b|\\bssl\\b|unexpected end of file|broken pipe|" +
        "bad archive|untrusted signature|\\beof\\b|host is down|software caused connection abort|resolv")

    /** What kind of failure an apk log tail shows. */
    fun classify(log: String): Fail = when {
        Regex("(?i)no space left|enospc|disk full").containsMatchIn(log) -> Fail.SPACE
        network.containsMatchIn(log) -> Fail.NETWORK
        Regex("(?i)unable to select packages|no such package|breaks: world|not found").containsMatchIn(log) -> Fail.MISSING
        else -> Fail.OTHER
    }

    /** Seconds before try [attempt] (2 → 3 s, 3 → 6, 4 → 12, 5 → 24, then 30). */
    fun backoffSec(attempt: Int): Int = if (attempt <= 1) 0 else minOf(30, 3 shl (attempt - 2))

    /** After a failure on mirror [cur] that has now failed [failsHere] times in a row: which mirror next, and its count. */
    fun nextMirror(cur: Int, failsHere: Int, size: Int): Pair<Int, Int> =
        if (failsHere >= 2 && size > 1) ((cur + 1) % size) to 0 else cur to failsHere

    /** The plain status line while waiting to retry. */
    fun retryLine(f: Fail, attempt: Int, tries: Int, newHost: String?): String {
        val what = when (f) {
            Fail.NETWORK -> "Connection dropped."
            Fail.MISSING -> "The package list changed."
            Fail.SPACE -> "The phone is out of space."
            Fail.OTHER -> "That step didn't finish."
        }
        return if (newHost != null) "$what Trying another server ($newHost), $attempt of $tries\u2026"
        else "$what Retrying ($attempt of $tries)\u2026"
    }

    /** The plain error once every try failed. */
    fun failLine(f: Fail, tail: String): String = when (f) {
        Fail.SPACE -> "The phone ran out of space. Free up about ${DesktopPlan.DISK_MB} MB, then tap Start to carry on."
        Fail.NETWORK, Fail.MISSING -> "The connection kept dropping, so the desktop isn't fully downloaded yet. What's downloaded so far is kept: tap Start to carry on."
        Fail.OTHER -> "The desktop couldn't be installed. Tap Start to try again; what's downloaded so far is kept." +
            tail.lines().lastOrNull { it.contains("ERROR", true) }?.let { " (" + it.substringAfter("ERROR:").trim().take(120) + ")" }.orEmpty()
    }

    /** Overall percent for step [i] of [n] when that step is [inner]% done. */
    fun pct(i: Int, n: Int, inner: Int): Int = ((i * 100 + inner.coerceIn(0, 100)) / n.coerceAtLeast(1)).coerceIn(0, 99)

    /** One step's outcome from the runner: exit code and the last few lines. */
    data class Result(val code: Int, val tail: String)

    /**
     * Runs the install. [exec] runs a script inside Alpine, feeding each output line to its callback; [status] gets
     * (percent or null, plain line); [sleep] waits in milliseconds. Returns null when installed, else the plain error.
     * [mirrors] defaults to [AlpineNet.MIRRORS]; [startMirror] is where the last install got to.
     */
    fun run(exec: (String, (String) -> Unit) -> Result, status: (Int?, String) -> Unit, sleep: (Long) -> Unit,
            mirrors: List<String> = AlpineNet.MIRRORS, startMirror: Int = 0, tries: Int = TRIES, onMirror: (Int) -> Unit = {}): String? {
        val steps: List<List<String>> = listOf(emptyList<String>()) + DesktopPlan.BATCHES
        val n = steps.size + 1
        var mirror = startMirror.coerceIn(0, (mirrors.size - 1).coerceAtLeast(0))
        var failsHere = 0
        var needUpdate = true
        for ((i, pkgs) in steps.withIndex()) {
            var attempt = 1
            while (true) {
                status(pct(i, n, 0), if (pkgs.isEmpty()) "Checking what to download\u2026" else "Downloading " + pkgs.joinToString(", ") + "\u2026")
                val r = exec(DesktopPlan.stepScript(mirrors[mirror], pkgs, update = needUpdate || pkgs.isEmpty())) { line ->
                    DesktopPlan.progressLine(line)?.let { (p, words) -> if (p != null && p < 100) status(pct(i, n, p), words) }
                }
                if (r.code == 0 && "BLAZE_STEP_OK" in r.tail) { needUpdate = false; failsHere = 0; break }
                val f = classify(r.tail)
                if (f == Fail.SPACE || attempt >= tries) return failLine(f, r.tail)
                attempt++
                failsHere++
                val (m, c) = nextMirror(mirror, failsHere, mirrors.size)
                val switched = m != mirror
                mirror = m; failsHere = c; needUpdate = true
                if (switched) onMirror(mirror)
                status(null, retryLine(f, attempt, tries, if (switched) AlpineNet.host(mirrors[mirror]) else null))
                sleep(backoffSec(attempt) * 1000L)
            }
        }
        status(pct(n - 1, n, 0), "Finishing\u2026")
        val r = exec(DesktopPlan.FINISH_SCRIPT) { }
        return if (r.code == 0 && "BLAZE_DESKTOP_INSTALLED" in r.tail) null else failLine(classify(r.tail), r.tail)
    }
}
