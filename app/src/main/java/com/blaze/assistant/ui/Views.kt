package com.blaze.assistant.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every screen Blaze has, in one place. Nothing was deleted in the 0.12 redesign: pages left the
 * sidebar and live here, reachable from /views, their own /command, Search, and open_view.
 *
 * [page] = a settings page id ("" = the settings list); [special] = a screen that isn't a settings page.
 */
data class BlazeView(val id: String, val name: String, val desc: String, val page: String? = null,
                     val special: String? = null, val aliases: List<String> = emptyList())

object Views {
    val all = listOf(
        BlazeView("chats", "Chats", "Every saved conversation", special = "chats", aliases = listOf("history")),
        BlazeView("design", "Design", "Live preview of pages Blaze builds", special = "design", aliases = listOf("preview", "studio")),
        BlazeView("memory", "Memory", "What Blaze remembers and has learned", page = "memory", aliases = listOf("learn", "learning")),
        BlazeView("jobs", "Jobs", "Running and past jobs, the terminal", page = "terminal", aliases = listOf("terminal", "shell")),
        BlazeView("schedule", "Schedule", "Routines and timed tasks", page = "routines", aliases = listOf("routines")),
        BlazeView("agents", "Agents", "Specialists and background agents", page = "specialists", aliases = listOf("specialists")),
        BlazeView("accounts", "Accounts", "Connected apps: Google, GitHub, Spotify, Notion", page = "connections", aliases = listOf("connections", "connectors")),
        BlazeView("router", "BlazeRouter", "Models, keys and free brains", page = "ai", aliases = listOf("brain", "models", "model", "keys")),
        BlazeView("skills", "Skills", "Skill library: on, off, restore", page = "skills"),
        BlazeView("gallery", "Gallery", "Images, music and files Blaze made", special = "gallery"),
        BlazeView("browser", "Browser", "Blaze's own browser and tabs", special = "browser"),
        BlazeView("doctor", "Doctor", "Phone health, self-check, crash report", page = "device", aliases = listOf("device", "diagnostics")),
        BlazeView("usage", "Usage", "Tokens and estimated cost", page = "usage", aliases = listOf("cost")),
        BlazeView("voice", "Voice", "Hey Blaze, spoken replies, call", page = "voice"),
        BlazeView("control", "Phone control", "Accessibility, permissions", page = "control"),
        BlazeView("proactive", "Proactive", "Speak up on its own, triggers", page = "proactive", aliases = listOf("triggers")),
        BlazeView("desktop", "Blaze Desktop", "Pair with Blaze on your computer", page = "desktop", aliases = listOf("pc", "pair")),
        BlazeView("commands", "Commands", "Slash commands and prompt skills", page = "commands"),
        BlazeView("dashboard", "Dashboard", "Local web dashboard", page = "dashboard"),
        BlazeView("account", "Blaze account", "Sign in, shared with Desktop", page = "account"),
        BlazeView("settings", "Settings", "Behaviour, voice, privacy, backup", page = ""),
    )

    fun find(id: String): BlazeView? {
        val k = id.trim().lowercase().removePrefix("/")
        return all.firstOrNull { it.id == k } ?: all.firstOrNull { k in it.aliases }
    }

    fun filter(q: String): List<BlazeView> {
        val f = q.trim().lowercase()
        if (f.isEmpty()) return all
        return all.filter { (it.name + " " + it.desc + " " + it.id + " " + it.aliases.joinToString(" ")).lowercase().contains(f) }
    }

    /** A request to open a view (from open_view, a /command, Search). ChatScreen consumes it. */
    data class Request(val id: String, val target: String = "", val n: Long = System.nanoTime())
    val request = MutableStateFlow<Request?>(null)
    fun open(id: String, target: String = "") { request.value = Request(id, target) }

    /** The view shown over the chat right now ("" = plain chat). Shown as the "DESIGN ✕ Exit" chip. */
    val showing = MutableStateFlow("")

    /** What the Design view previews: a URL or a file path. */
    val designTarget = MutableStateFlow("")

    // ---------------- open_view tool ----------------

    const val TOOL = "open_view"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Switch Blaze's own screen to one of its views: design (live preview of a web page, HTML file or local server you built), " +
            "memory, jobs, schedule, agents, accounts, router (models/keys), skills, gallery, browser, doctor, usage, settings, chats. " +
            "The user can always tap Exit to come back to the chat. For design pass target = the file path (e.g. ~/site/index.html) or URL to preview.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("view", JSONObject().put("type", "string").put("enum", JSONArray(all.map { it.id })))
            .put("target", JSONObject().put("type", "string").put("description", "design: file path or URL to preview"))
            .put("ask", JSONObject().put("type", "boolean").put("description", "true = ask the user before switching"))
            .put("reason", JSONObject().put("type", "string").put("description", "one short line: why switch")))
            .put("required", JSONArray(listOf("view")))))

    const val PROMPT_LINE = "\nVIEWS: when a task is visual (a website, page, design, poster, 3D), call open_view view=design with the file or URL as target, without asking. " +
        "For other views, set ask=true unless the user asked for that view."

    /** Plain-words step line for the tool. */
    fun step(args: JSONObject): String = "Opening " + (find(args.optString("view"))?.name ?: args.optString("view"))
}
