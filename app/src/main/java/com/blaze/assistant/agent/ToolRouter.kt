package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool diet (speed plan step 1, the "mixture of experts" idea): instead of sending all ~95 tool
 * schemas on every turn, send a small core set plus only the groups this request looks like it
 * needs. Fewer input tokens means a faster first word on every provider. The model can always
 * pull in more with load_tools, and any tool that isn't in a group is always sent, so nothing
 * becomes unreachable.
 */
object ToolRouter {

    /** Always sent. */
    private val core = setOf(
        "remember", "forget", "open_view", "about_blaze", "blazerouter", "processes", "process", "recall_memory", "device_status", "list_apps", "blaze_self", "ask_user", "self_test",
        "web_search", "fetch_page", "load_tools", "find_tool", "goal", "use_skill", "search_past_chats", "run_skill",
        // Basic phone control stays in core: it's the most common thing people ask for.
        "run_plan", "open_app", "search_in_app", "read_screen",
        // Calls: "loudspeaker", "mute", "hang up" must work mid-call without guessing a group.
        "call_control",
        // Background agents: always on hand so long work can be handed off from any request.
        "start_agent", "list_agents", "check_agent", "message_agent", "cancel_agent"
    )

    data class Group(val id: String, val about: String, val match: Regex, val names: (String) -> Boolean)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val groups = listOf(
        Group("screen", "fine-grained screen control: tap, type, scroll, buttons, screenshots, do_actions",
            r("""\b(tap|click|press|scroll|swipe|type|screen|button|screenshot|look at|watch|watcher|whenever|pops? up|sign ?in|log ?in)\b"""),
            { it in setOf("tap", "type_text", "scroll", "press_button", "do_actions", "look_at_screen", "wait", "viewport", "open_url", "navigate_to", "share_to_app", "watch") }),
        Group("messages", "texts, calls, contacts, WhatsApp/Telegram, notifications, USSD",
            r("""\b(text|sms|message|whatsapp|telegram|reply|call|ring|dial|phone|contact|number|notification|missed|unread|airtime|bundle|balance|ussd|mtn|airtel|zamtel|mobile money|momo)\b|\*\d"""),
            { it in setOf("send_sms", "compose_sms", "read_messages", "call_contact", "call_number", "dial", "find_contact", "recent_calls",
                "message_contact", "send_text", "reply_to_notification", "recent_notifications", "run_ussd", "add_contact", "edit_contact") }),
        Group("device", "alarms, timers, volume, settings toggles, battery, media",
            r("""\b(alarm|timer|volume|mute|loud|wifi|wi-fi|bluetooth|torch|flash|brightness|dnd|disturb|airplane|battery|setting|pause|play|next|skip|music|song|clipboard|copy|paste|notif\w*|share|ringer|silent|vibrate|rotat\w*|hotspot|nfc|location|split|power|screenshot|lock|usage|uninstall|camera|qr|photo|installed|apps?|secure folder|private space|phone model|android version|where am i|near me|nearby|gps|my location|address)\b"""),
            { it in setOf("set_alarm", "set_timer", "set_volume", "toggle_setting", "device_setting", "battery_level", "media_control", "get_location") || it in com.blaze.assistant.phone.AndroidControl.names }),
        Group("schedule", "calendar, routines, triggers (when X do Y)",
            r("""\b(calendar|event|meeting|schedule|remind|reminder|every|daily|tomorrow|tonight|morning|routine|when|whenever|appointment)\b"""),
            { it in setOf("calendar_events", "add_calendar_event", "gcal_events", "gcal_create", "create_routine", "list_routines", "delete_routine", "schedule_command",
                "create_trigger", "list_triggers", "delete_trigger", "move_event", "delete_event") }),
        Group("google", "Gmail, Drive, Google contacts",
            r("""\b(e-?mail|gmail|inbox|mail|drive|doc|document|file)\b"""),
            { it.startsWith("gmail_") || it == "drive_search" || it == "gcontacts_search" }),
        Group("browser", "Blaze's own background browser: open sites, click, fill forms, log in, private tabs, browser VPN by country",
            r("""\b(site|website|web ?page|browser|log ?in|sign ?in|form|book|order|buy|account|portal|vpn|incognito|private tab|from (?:the )?(?:us|uk|usa|another country)|\.com|\.org|\.zm|https?://)"""),
            { it.startsWith("browser_") }),
        Group("terminal", "Linux shell, code, files, Termux",
            r("""\b(shell|terminal|linux|command|script|code|coding|python|node|js|javascript|npm|pip|git|github|repo|clone|commit|push|pull|branch|api|json|csv|http|curl|download|install|file|files|folder|termux|run|compile|build|debug|server|program|calculate|convert)\b"""),
            { it in setOf("run_shell", "linux_install", "terminal_jobs", "terminal_stop", "termux_run", "git", "schedule_command", "preview_server") || it in com.blaze.assistant.terminal.DevTools.names }),
        Group("apps", "GitHub, Notion, Slack, Spotify connectors",
            r("""\b(github|repo|issue|pull request|pr|notion|slack|spotify|playlist|song|music)\b"""),
            { it.startsWith("github_") || it.startsWith("notion_") || it.startsWith("slack_") || it.startsWith("spotify_") }),
        Group("media", "make images (generate_image), beats/music and other media in the terminal, the Gallery",
            r("""\b(image|images|picture|pictures|pic|photo|draw|drawing|paint|art|artwork|logo|icon|wallpaper|poster|illustration|generate|create|design|beat|beats|instrumental|music|song|melody|audio|sound|wav|mp3|gallery|meme|sticker|avatar)\b"""),
            { it in setOf("generate_image", "gallery", "run_code", "file_list", "file_read", "file_write", "run_shell", "terminal_status") }),
        Group("pc", "the user's paired computer running Blaze Desktop (ask_pc)",
            r("""\b(pc|computer|laptop|desktop|my mac|macbook|on windows)\b"""),
            { it == "ask_pc" }),
        Group("more", "everything else: watchers, triggers, goals, standing permissions, skill packs and other rarely used tools",
            Regex("(?!)"), { false }),
        Group("extras", "learned skills, helpers working in parallel, screen memory",
            r("""\b(skill|learn|delegate|parallel|at once|earlier on screen|saw)\b"""),
            { it in setOf("skill_manage", "delegate", "search_screen_memory", "install_skill", "write_skill", "skill_file") }),
    )

    private fun nameOf(t: Any?): String = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    /** A tool's group; tools no group claims (and that aren't core) fall into "more", sent only on request. */
    fun groupOf(name: String): String? = groups.firstOrNull { it.names(name) }?.id
        ?: if (name in core || name == "load_tools" || name.isBlank() || !Settings.bool("tool_lean", true)) null else "more"

    /** Tool groups the text looks like it needs. */
    fun guess(text: String): Set<String> = groups.filter { it.match.containsMatchIn(text) }.map { it.id }.toSet()

    /** Groups of every tool this chat has already used (so follow-ups keep working). */
    fun usedIn(history: JSONArray): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") groupOf(m.optString("name"))?.let { out += it }
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) groupOf(nameOf(calls.opt(j)))?.let { out += it }
        }
        return out
    }

    val enabled get() = Settings.bool("tool_diet", true)

    /** The tools to send this step. */
    fun select(all: JSONArray, groupsOn: Set<String>): JSONArray {
        if (!enabled) return all
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.opt(i)
            val n = nameOf(t)
            val g = groupOf(n)
            if (n in core || g == null || g in groupsOn) out.put(t)
        }
        return out
    }

    fun loadTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "load_tools")
        .put("description", "Load more tools when the ones you have can't do the job. Groups: " +
            groups.joinToString("; ") { "${it.id} (${it.about})" } + ". They're available from your next step.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("groups", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")
                .put("enum", JSONArray(groups.map { it.id }))))).put("required", JSONArray(listOf("groups")))))
}
