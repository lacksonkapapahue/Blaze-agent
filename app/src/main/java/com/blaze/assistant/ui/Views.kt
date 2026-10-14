package com.blaze.assistant.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every screen Blaze has, in one place. Nothing was deleted in the 0.12 redesign: pages left the
 * sidebar and live here, reachable from /views, their own /command, Search, and open_view.
 * v0.24.0: the Archive (old chat list) is gone with the conversation switcher; old chats stay on disk and in
 * the journal, so Blaze still finds them with search_chat / search_past_chats.
 *
 * [page] = a settings page id ("" = the settings list); [special] = a screen that isn't a settings page.
 */
data class BlazeView(val id: String, val name: String, val desc: String, val page: String? = null,
                     val special: String? = null, val aliases: List<String> = emptyList())

object Views {
    val all = listOf(
        BlazeView("projects", "Projects", "Ongoing efforts with their own threads, brief and instructions", special = "projects", aliases = listOf("project")),
        BlazeView("design", "Design", "Live preview of anything visual: 3D scenes, art, sites, UI mockups, diagrams, charts, posters, slides", special = "design", aliases = listOf("preview", "studio", "3d", "designs")),
        BlazeView("memory", "Memory", "What Blaze remembers and has learned", page = "memory", aliases = listOf("learning")),
        BlazeView("learn", "Learn", "Flashcards with spaced repetition, Classroom, low-data mode", page = "learn", aliases = listOf("flashcards", "study", "student", "classroom")),
        BlazeView("seller", "Seller", "Brand kit, store digest, posts, ads and listings", page = "seller", aliases = listOf("store", "shop", "marketing", "marketer", "brand")),
        BlazeView("jobs", "Jobs", "Running and past jobs, the terminal", page = "terminal", aliases = listOf("terminal", "shell")),
        BlazeView("schedule", "Schedule", "Routines and timed tasks", page = "routines", aliases = listOf("routines")),
        BlazeView("agents", "Agents", "Specialists and background agents", page = "specialists", aliases = listOf("specialists")),
        BlazeView("accounts", "Accounts", "Connected apps: Google, GitHub, Spotify, Notion", page = "connections", aliases = listOf("connections", "connectors")),
        BlazeView("router", "Brain routing", "Auto (free) or a fixed model; the router dashboard is gone (v0.24.4)", page = "ai", aliases = listOf("blazerouter", "dashboard-router")),
        BlazeView("brain", "Brain", "Blaze Cloud: the best free model for every message", page = "ai", aliases = listOf("models", "model", "keys", "classic", "cloud")),
        BlazeView("skills", "Skills", "Skill library: on, off, restore", page = "skills"),
        BlazeView("gallery", "Gallery", "Images, music and files Blaze made", special = "gallery"),
        BlazeView("browser", "Browser", "Blaze's own browser and tabs", special = "browser"),
        BlazeView("doctor", "Doctor", "Phone health, self-check, crash report", page = "device", aliases = listOf("device", "diagnostics")),
        BlazeView("usage", "Usage", "Tokens and estimated cost", page = "usage", aliases = listOf("cost")),
        BlazeView("voice", "Voice", "Hey Blaze, spoken replies, call", page = "voice"),
        BlazeView("control", "Phone control", "Accessibility, permissions", page = "control"),
        BlazeView("logins", "Saved logins", "Sign-ins from the secure form: view, edit, delete", page = "logins", aliases = listOf("vault", "passwords", "logins")),
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

    // ---------------- v0.24.6: ask before switching ----------------

    /** "Open in Design?": a chip in the chat that opens the view on one tap (Luke: Blaze asks, it doesn't jump). */
    data class Offer(val id: String, val target: String = "", val n: Long = System.nanoTime())
    val offer = MutableStateFlow<Offer?>(null)

    fun offerLabel(id: String): String = "Open in ${find(id)?.name ?: id.replaceFirstChar { it.uppercase() }}?"

    /** True when the user's own words asked to open / show / switch to this view (then it opens straight away). */
    fun askedFor(text: String, id: String): Boolean {
        val v = find(id) ?: return false
        val names = (listOf(v.id, v.name) + v.aliases).map { Regex.escape(it.lowercase()) }.joinToString("|")
        val t = text.lowercase().trim()
        if (t.startsWith("/${v.id}")) return true
        return Regex("""\b(open|show|switch to|go to|take me to|bring up|launch)\b[^.?!]{0,30}\b($names)\b""").containsMatchIn(t) ||
            Regex("""\b(open (it|that|this)|show (it|me)|let me see|so i can see)\b""").containsMatchIn(t) && id == "design"
    }

    /**
     * Opens [id] now when the user asked for it (or it's already on screen), else offers it with the chip.
     * Returns true when it opened. Call on the main thread.
     */
    fun openOrOffer(id: String, target: String, userText: String, autopilot: Boolean = false): Boolean {
        if (autopilot || showing.value == id || askedFor(userText, id)) { offer.value = null; open(id, target); return true }
        offer.value = Offer(id, target)
        return false
    }

    /** The view shown over the chat right now ("" = plain chat). Shown as the "DESIGN ✕ Exit" chip. */
    val showing = MutableStateFlow("")

    /** What the Design view previews: a URL or a file path. */
    val designTarget = MutableStateFlow("")

    // ---------------- open_view tool ----------------

    const val TOOL = "open_view"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Switch Blaze's own screen to one of its views: design (live preview of anything visual: a 3D scene, art, a site, UI mockup, diagram, chart, poster or slides; with no target it shows the current design; make or change designs with the design tool), " +
            "learn (flashcard decks; open it after flashcards add), memory, jobs, schedule, projects, agents, accounts, router (models/keys), skills, gallery, browser, doctor, usage, settings. " +
            "Output that fits a view is made there and the view opened, never dumped in chat. " +
            "The user can always tap Exit to come back to the chat. For design pass target = the file path (e.g. ~/site/index.html) or URL to preview, or nothing for the current design.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("view", JSONObject().put("type", "string").put("enum", JSONArray(all.map { it.id })))
            .put("target", JSONObject().put("type", "string").put("description", "design: file path or URL to preview"))
            .put("ask", JSONObject().put("type", "boolean").put("description", "true = ask the user before switching"))
            .put("reason", JSONObject().put("type", "string").put("description", "one short line: why switch")))
            .put("required", JSONArray(listOf("view")))))

    /** Kept for older callers; the full VIEWS block is agent/ViewRouter.PROMPT since v0.23.0. */
    const val PROMPT_LINE = "\nVIEWS: when a task is visual (a 3D scene, website, page, UI mockup, diagram, chart, poster, art), make it with the design tool (it opens Design), or call open_view view=design with the file or URL as target, without asking. " +
        "For other views, set ask=true unless the user asked for that view."

    /** Plain-words step line for the tool. */
    fun step(args: JSONObject): String = "Opening " + (find(args.optString("view"))?.name ?: args.optString("view"))
}
