package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Composer modes (v1.14, ported from desktop modes.go).
 *
 *   Plan       read + look only. The tool list is filtered AND every other call is refused at
 *              dispatch (Agent.Session.runTool), so a model that ignores the prompt still can't act.
 *   Build      every tool (the old behaviour, default).
 *   Auto       risky actions show the confirm card (default).
 *   Autopilot  ordinary confirms are approved on their own; destructive actions (delete, wipe,
 *              uninstall, money, messages and calls in the user's name) still show the confirm card.
 *              Questions get an automatic "decide yourself" after a short wait. A run is capped at
 *              [apSteps] steps and [apMinutes] minutes.
 *
 * Prefs (backward compatible, new keys only): work_mode, approval, autopilot_steps, autopilot_minutes.
 */
object Modes {
    private val _work = MutableStateFlow(Settings.str("work_mode", "build").let { if (it == "plan") "plan" else "build" })
    val work: StateFlow<String> = _work
    private val _approval = MutableStateFlow(Settings.str("approval", "auto").let { if (it == "autopilot") "autopilot" else "auto" })
    val approval: StateFlow<String> = _approval

    val plan get() = _work.value == "plan"
    val autopilot get() = _approval.value == "autopilot"
    val apSteps get() = Settings.str("autopilot_steps", "150").toIntOrNull()?.coerceIn(20, 400) ?: 150
    val apMinutes get() = Settings.str("autopilot_minutes", "45").toIntOrNull()?.coerceIn(5, 240) ?: 45

    fun setWork(m: String) { val v = if (m == "plan") "plan" else "build"; Settings.setStr("work_mode", v); _work.value = v }
    fun setApproval(m: String) { val v = if (m == "autopilot") "autopilot" else "auto"; Settings.setStr("approval", v); _approval.value = v }
    fun toggleWork() = setWork(if (plan) "build" else "plan")
    fun toggleApproval() = setApproval(if (autopilot) "auto" else "autopilot")

    const val AUTO_REPLY = "The user is away and can't answer. Make the decision yourself pragmatically, based on your understanding and best practice, note the assumption, and continue. If the task is already done and verified, don't start new work: finish with your summary and the assumptions you made."
    const val AUTO_PREFIX = "[Autopilot] "

    // ---------------- Plan ----------------

    /** Tools that only read or look. Anything else is refused in Plan. */
    val planTools = setOf(
        "web_search", "fetch_page", "browser_open", "browser_read", "browser_scroll", "browser_back", "browser_tabs",
        "recall_memory", "search_past_chats", "search_screen_memory",
        "device_status", "battery_level", "list_apps", "app_usage", "read_screen", "look_at_screen", "self_test",
        "recent_notifications", "read_messages", "recent_calls", "find_contact", "calendar_events",
        "gcal_events", "gmail_search", "gmail_read", "drive_search", "gcontacts_search",
        "github_issues", "github_my_prs", "github_my_repos", "github_notifications", "github_search_repos",
        "notion_search", "slack_read", "slack_channels", "spotify_now_playing",
        "file_read", "file_list", "file_search", "terminal_status", "terminal_jobs",
        "processes", "list_routines", "list_triggers", "use_skill", "skill_file",
        "find_tool", "load_tools", "ask_user", "get_location",
        "list_agents", "check_agent",
        // checked by action below
        "git", "clipboard", "call_control", "blaze_self", "process", "gallery",
    )

    private val actionReadOnly = mapOf(
        "clipboard" to setOf("get", "get_image", "history"),
        "call_control" to setOf("status"),
        "blaze_self" to setOf("setup_check", "status"),
        "process" to setOf("details", "status"),
        "gallery" to setOf("list"),
    )

    private val gitReadOnly = setOf("status", "diff", "log", "show", "blame", "ls-files", "rev-parse", "describe", "shortlog", "grep",
        "ls-tree", "cat-file", "rev-list", "show-ref", "for-each-ref", "branch", "tag", "remote", "reflog", "stash", "config")

    /** "" when a git call only reads (port of desktop planGit, kept to what the Android git tool exposes). */
    fun planGit(args: JSONObject): String {
        val act = args.optString("action").lowercase()
        if (act.isNotEmpty() && act != "raw") return if (act in setOf("status", "log", "diff")) "" else "git $act changes the repository"
        val f = args.optString("args", args.optString("command")).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (f.isNotEmpty() && (f[0] == "git" || f[0].startsWith("--no-pager"))) f.removeAt(0)
        if (f.isNotEmpty() && f[0] == "-C" && f.size > 1) { f.removeAt(0); f.removeAt(0) }
        if (f.isEmpty()) return ""
        val sub = f[0]; val rest = f.drop(1)
        if (sub !in gitReadOnly) return "git $sub changes the repository"
        fun has(vararg xs: String) = rest.any { a -> xs.any { x -> a == x || a.startsWith("$x=") } }
        when (sub) {
            "branch" -> if (rest.any { !it.startsWith("-") } || has("-d", "-D", "-m", "-M", "-c", "-C", "--delete", "--move", "--copy", "--set-upstream-to", "-u", "--unset-upstream", "-f", "--force")) return "git branch with those arguments changes branches"
            "tag" -> if (!has("-l", "--list") && (rest.any { !it.startsWith("-") } || has("-d", "--delete", "-a", "-s", "-f", "-m"))) return "git tag with those arguments changes tags"
            "remote" -> if (rest.isNotEmpty() && rest[0] !in setOf("-v", "show", "get-url")) return "git remote ${rest[0]} changes remotes"
            "reflog" -> if (rest.isNotEmpty() && rest[0] in setOf("expire", "delete")) return "git reflog ${rest[0]} changes history"
            "stash" -> if (rest.isEmpty() || rest[0] !in setOf("list", "show")) return "git stash changes the working tree"
            "config" -> if (!has("--get", "--get-all", "--list", "-l", "--get-regexp")) return "git config without --get/--list changes settings"
        }
        if (sub in setOf("diff", "log", "show") && has("--output", "-o")) return "writing git output to a file"
        return ""
    }

    /** Why Plan refuses this call ("" = allowed). */
    fun planBlock(name: String, args: JSONObject): String {
        // MCP tools: only those that declare readOnlyHint (Desktop's ReadOnly rule).
        if (com.blaze.assistant.connect.Mcp.handles(name)) return if (com.blaze.assistant.connect.Mcp.readOnly(name)) "" else "$name can change things"
        if (name !in planTools) return "$name can change things"
        actionReadOnly[name]?.let { ok ->
            val a = args.optString("action").ifBlank { if (name == "gallery") "list" else "" }
            if (a !in ok) return "$name ${a.ifBlank { "(no action)" }} can change things"
        }
        if (name == "git") return planGit(args)
        return ""
    }

    /** The refusal the model gets in Plan ("" = allowed). [agentPlan]: a background agent started in Plan. */
    fun block(name: String, args: JSONObject, agentPlan: Boolean = false): String {
        if (!plan && !agentPlan) return ""
        val why = planBlock(name, args)
        if (why.isEmpty()) return ""
        return "Refused: Blaze is in PLAN mode (read and look only) and $why. Don't retry it. Finish investigating with read-only tools, then present the plan: exactly what you would do, step by step, and the risks. The user switches to Build to let you carry it out."
    }

    /** Plan shows the model only tools it may use (the dispatch check above still guards). */
    fun filterTools(all: org.json.JSONArray, agentPlan: Boolean = false): org.json.JSONArray {
        if (!plan && !agentPlan) return all
        return org.json.JSONArray().apply {
            for (i in 0 until all.length()) {
                val t = all.getJSONObject(i)
                val n = t.optJSONObject("function")?.optString("name").orEmpty()
                if (n in planTools || (com.blaze.assistant.connect.Mcp.handles(n) && com.blaze.assistant.connect.Mcp.readOnly(n))) put(t)
            }
        }
    }

    // ---------------- Autopilot ----------------

    /** Actions that keep the confirm card even in Autopilot: can't be undone, spend money, or speak for the user. */
    private val destructive = Regex(
        "\\b(delete|deleting|remove|erase|wipe|format|factory|reset|uninstall|clear data|rm\\s+-|drop\\s+table|truncate|" +
            "pay|payment|purchase|buy|order|checkout|check out|transfer|send money|subscribe|unsubscribe|cancel subscription|" +
            "send|text|sms|message|email|mail|post|publish|reply|call|dial|share|whatsapp|ussd|\\*\\d+#)\\b",
        RegexOption.IGNORE_CASE)

    fun destructive(title: String, detail: String): Boolean = destructive.containsMatchIn("$title $detail")

    private val questionRe = Regex("(?i)(\\?\\s*(\\*\\*)?\\s*$|\\b(would you like|do you want|should i|shall i|let me know|which (one|option)|can you (confirm|tell|provide)|could you (confirm|tell|provide)|please (confirm|choose|provide|tell))\\b)")

    /** True when a reply that ran no tools ends by asking the user something. */
    fun asksUser(text: String): Boolean {
        val t = text.trim(); if (t.isEmpty()) return false
        val tail = t.takeLast(400).lines().takeLast(3).joinToString("\n").trim()
        return questionRe.containsMatchIn(tail)
    }

    /** Tells the model which modes are on. */
    fun promptLine(agentPlan: Boolean = false): String = buildString {
        append("\nMODE: ")
        if (plan || agentPlan) append("PLAN (read and look only). You can search and read the web, read files, check memory, mail, calendar, messages and the screen, and run read-only git. You cannot send, tap, type, change settings, write files or run commands: such calls are refused. Investigate, then give a clear plan: what you found, exactly what you'd do step by step, and the risks. The user switches to Build to let you do it. ")
        else append("BUILD (full access). ")
        if (autopilot) append("AUTOPILOT: the user is away. Don't stop to ask questions or wait for approval; make reasonable decisions, note each assumption, and keep going until the task is fully done and verified. Deleting things, spending money and messaging or calling people still need the user's tap, so do everything else first. Finish with a short summary and the assumptions you made. ")
        else append("AUTO approval: risky actions show the user a confirm card first. ")
    }
}
