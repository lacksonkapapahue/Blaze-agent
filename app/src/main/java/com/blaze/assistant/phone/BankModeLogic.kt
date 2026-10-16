package com.blaze.assistant.phone

/**
 * Bank mode (v0.30.9), the pure part: command parsing, the secure-settings list edit, which apps count as bank apps,
 * and the auto-mode decision. No Android imports, unit-tested in BankModeLogicTest.
 *
 * Why: banking apps refuse to run while a non-Play accessibility service is on (and some while another app draws
 * overlays). Bank mode removes every Blaze overlay and switches Blaze phone control off for real (disableSelf), so the
 * bank app sees nothing; the user switches it back on in Accessibility settings and Blaze picks up where it was.
 */
object BankModeLogic {

    /** What a /bank-mode command or a "bank mode on/off" phrase asks for. */
    sealed class Cmd {
        /** Turn on; [app] (a name or package) is opened right after, e.g. "/bank-mode open Zanaco". */
        data class On(val app: String? = null) : Cmd()
        object Off : Cmd()
        object Toggle : Cmd()
        object Status : Cmd()
        /** Add / remove an app to the bank-apps list (auto mode and the one-time suggestion). */
        data class Add(val app: String) : Cmd()
        data class Remove(val app: String) : Cmd()
        object Apps : Cmd()
    }

    /** Command names that mean bank mode: /bank-mode, /bankmode, /bank_mode, /bank. */
    val NAMES = setOf("bank-mode", "bankmode", "bank_mode", "bank")

    private fun norm(s: String) = s.trim().lowercase()
        .replace(Regex("[?!.,]+$"), "").replace(Regex("\\s+"), " ")
        .replace(Regex("^(hey blaze|ok blaze|okay blaze|blaze)[, ]+"), "")
        .replace(Regex("^(please|can you|could you|i want to|i need to|let'?s)[, ]+"), "")
        .replace(Regex("( please| now| for me| blaze)+$"), "").trim()

    /** The words after the command name (or after "bank mode" in a phrase) → a command. */
    private fun fromArg(arg0: String): Cmd {
        val arg = arg0.trim()
        val low = arg.lowercase()
        return when {
            low.isEmpty() -> Cmd.Toggle
            low in setOf("on", "start", "enable", "enabled", "activate", "yes") -> Cmd.On()
            low in setOf("off", "stop", "disable", "disabled", "deactivate", "no", "exit", "end") -> Cmd.Off
            low in setOf("status", "state", "?", "check") -> Cmd.Status
            low in setOf("apps", "list", "banks", "add", "remove", "forget") -> Cmd.Apps
            low.startsWith("add ") -> arg.substring(4).trim().let { if (it.isEmpty()) Cmd.Apps else Cmd.Add(it) }
            low.startsWith("remove ") -> arg.substring(7).trim().let { if (it.isEmpty()) Cmd.Apps else Cmd.Remove(it) }
            low.startsWith("forget ") -> arg.substring(7).trim().let { if (it.isEmpty()) Cmd.Apps else Cmd.Remove(it) }
            else -> {
                // "on and open Zanaco", "open Zanaco", "on Zanaco", "Zanaco", "with zanaco", "for zanaco"
                val app = arg.replace(Regex("^(on|start|enable)(\\s+(and|then))?\\s+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^(and\\s+|then\\s+)?(open|launch|start|run|go to|for|with)\\s+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^(the )?", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "").trim()
                if (app.isEmpty()) Cmd.On() else Cmd.On(app)
            }
        }
    }

    /**
     * "/bank-mode", "/bank-mode on|off|status|apps", "/bank-mode open Zanaco", "/bank-mode add Zanaco", and plain phrases:
     * "bank mode on", "turn on bank mode", "bank mode off", "disable bank mode", "open Zanaco in bank mode",
     * "bank mode open Zanaco". Null when the text isn't about bank mode.
     */
    fun parse(text: String): Cmd? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > 120 || raw.contains('\n')) return null
        if (raw.startsWith("/")) {
            if (raw.startsWith("//")) return null
            val name = raw.removePrefix("/").substringBefore(' ').lowercase()
            if (name !in NAMES) return null
            return fromArg(raw.removePrefix("/").substringAfter(' ', ""))
        }
        val t = norm(raw)
        val bm = "bank ?mode"
        Regex("^(turn on|switch on|enable|start|activate|go into|enter|use|put (the phone|my phone|me) in(to)?) (the )?$bm$").find(t)?.let { return Cmd.On() }
        Regex("^(turn off|switch off|disable|stop|deactivate|exit|leave|end|quit) (the )?$bm$").find(t)?.let { return Cmd.Off }
        Regex("^(turn|switch) (the )?$bm (on|off)$").find(t)?.let { return if (it.groupValues[3] == "on") Cmd.On() else Cmd.Off }
        Regex("^(is )?(the )?$bm (on|off)\\?*$").find(t)?.let { m ->
            if (m.groupValues[1].isNotEmpty()) return Cmd.Status
            return if (m.groupValues[3] == "on") Cmd.On() else Cmd.Off
        }
        Regex("^(is )?$bm (status|state)$|^is $bm (on|active|enabled)$").find(t)?.let { return Cmd.Status }
        // "open zanaco in bank mode", "open zanaco with bank mode on"
        Regex("^(open|launch|start) (.+?) (in|with|using) $bm( on)?$").find(t)?.let { m ->
            return Cmd.On(originalCase(raw, m.groupValues[2]))
        }
        // "bank mode open zanaco", "bank mode on and open zanaco", "bank mode zanaco" only with an open verb
        Regex("^$bm (on )?(and |then )?(open|launch|start) (.+)$").find(t)?.let { m -> return Cmd.On(originalCase(raw, m.groupValues[4])) }
        Regex("^$bm$").find(t)?.let { return Cmd.On() }
        return null
    }

    /** [lowerPart] as the user typed it (so "Zanaco" keeps its capital), falling back to the lowercase text. */
    private fun originalCase(raw: String, lowerPart: String): String {
        val i = raw.lowercase().indexOf(lowerPart)
        val s = if (i >= 0) raw.substring(i, i + lowerPart.length) else lowerPart
        return s.trim().replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "").trim()
    }

    // ---------- Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES ----------

    /** "com.x/.Svc" → "com.x/com.x.Svc"; null when it isn't "package/class". */
    fun expand(entry: String): String? {
        val e = entry.trim()
        val slash = e.indexOf('/')
        if (slash <= 0 || slash == e.length - 1) return null
        val pkg = e.substring(0, slash); val cls = e.substring(slash + 1)
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }

    /** Same component, whether written short ("pkg/.Cls") or long ("pkg/pkg.Cls"). Case matters, as on Android. */
    fun sameComponent(a: String, b: String): Boolean {
        val x = expand(a) ?: return false
        val y = expand(b) ?: return false
        return x == y
    }

    private fun entries(secure: String?): List<String> = secure.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    fun contains(secure: String?, component: String): Boolean = entries(secure).any { sameComponent(it, component) }

    /** The list with [component] added once at the end (other services kept, in order). */
    fun add(secure: String?, component: String): String {
        val list = entries(secure)
        if (list.any { sameComponent(it, component) }) return list.joinToString(":")
        return (list + component).joinToString(":")
    }

    /** The list with every spelling of [component] taken out (other services kept, in order). "" when nothing is left. */
    fun remove(secure: String?, component: String): String =
        entries(secure).filterNot { sameComponent(it, component) }.joinToString(":")

    // ---------- Which apps are bank apps ----------

    /**
     * Words that make an app look like a bank or mobile-money app, for the one-time "Turn on Bank mode?" suggestion.
     * Matched against the app's name and package as whole words. The user's own list ([chosen]) always counts.
     */
    val BANK_WORDS = listOf("bank", "banking", "zanaco", "stanbic", "absa", "fnb", "ecobank", "standard chartered", "sc mobile",
        "atlas mara", "indo zambia", "investrust", "natsave", "momo", "airtel money", "mpesa", "m-pesa", "zamtel kwacha", "kwacha")

    /** Words for apps that aren't banks even if a bank word shows up ("food bank", "power bank"). */
    private val NOT_BANK = listOf("power bank", "powerbank", "food bank", "question bank", "bankruptcy", "piggy bank game")

    fun looksLikeBank(pkg: String, label: String, extraWords: List<String> = emptyList()): Boolean {
        val hay = (label + " " + pkg.replace('.', ' ').replace('_', ' ')).lowercase()
        if (NOT_BANK.any { hay.contains(it) }) return false
        return (BANK_WORDS + extraWords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }).any { w ->
            Regex("(^|[^a-z0-9])" + Regex.escape(w) + "([^a-z0-9]|$)").containsMatchIn(hay)
        }
    }

    /** The bank-apps list as stored ("pkg1,pkg2"), cleaned. */
    fun parseApps(stored: String): Set<String> = stored.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    fun storeApps(apps: Collection<String>): String = apps.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /**
     * Suggest bank mode for [pkg] once: it looks like a bank (or is in [chosen]), bank mode is off, Blaze isn't the app,
     * and it wasn't suggested before ([suggested]).
     */
    fun shouldSuggest(pkg: String, label: String, ownPkg: String, bankModeOn: Boolean, chosen: Set<String>, suggested: Set<String>): Boolean {
        if (pkg.isBlank() || pkg == ownPkg || bankModeOn || pkg in suggested) return false
        return pkg in chosen || looksLikeBank(pkg, label)
    }

    // ---------- Auto mode (needs WRITE_SECURE_SETTINGS) ----------

    enum class Auto { NOTHING, PAUSE, RESUME }

    /** Windows that come and go over any app (shade, keyboard, permission pop-ups): they never end a bank visit. */
    fun transient(pkg: String): Boolean = pkg.isBlank() || pkg == "com.android.systemui" || pkg == "android" ||
        pkg.contains("inputmethod") || pkg.endsWith(".keyboard") || pkg.contains("permissioncontroller") ||
        pkg == "com.google.android.gms" || pkg.contains("biometric")

    /**
     * The auto-mode step for a new foreground app. [pausedFor] is the bank app Blaze paused itself for ("" = not paused
     * by auto mode). [manualOn] = the user turned bank mode on by hand (auto mode then never turns Blaze back on).
     */
    fun autoStep(fg: String, ownPkg: String, chosen: Set<String>, pausedFor: String, manualOn: Boolean, allowed: Boolean): Auto {
        if (!allowed || transient(fg)) return Auto.NOTHING
        if (pausedFor.isEmpty()) return if (!manualOn && fg in chosen) Auto.PAUSE else Auto.NOTHING
        // Paused for a bank app: stay paused while any chosen bank app is in front; resume when something else is.
        // Blaze itself in front (the user came back to chat) also resumes. [ownPkg] is kept for callers' clarity.
        if (fg in chosen && fg != ownPkg) return Auto.NOTHING
        return Auto.RESUME
    }

    /** From usage events (time, package, resumed?) the app most recently brought to the front, or "" when none. */
    fun lastForeground(events: List<Triple<Long, String, Boolean>>): String =
        events.filter { it.third && !transient(it.second) }.maxByOrNull { it.first }?.second.orEmpty()

    // ---------- Words ----------

    const val NOTE_TITLE = "Bank mode on, Blaze's phone control is paused"
    const val NOTE_TEXT = "Your banking apps can open now. Chat still works. Switch \u201cBlaze phone control\u201d back on when you're done."
    const val NOTE_ACTION = "Turn Blaze back on"

    /** What phone tools answer while bank mode is on. */
    const val TOOL_REPLY = "Bank mode is on, so Blaze's phone control is switched off on purpose (banking apps refuse to run while it is on). " +
        "Don't try to tap, read the screen or switch it back on yourself. Tell the user: to give Blaze its hands back, tap \u201cTurn Blaze back on\u201d " +
        "in the Bank mode notification (or type /bank-mode off) and switch on \u201cBlaze phone control\u201d. Chat, web and background tools still work."

    fun autoNoteTitle(appLabel: String) = "Blaze paused for $appLabel"
    fun autoNoteText() = "Phone control comes back by itself when you leave the app."
}
