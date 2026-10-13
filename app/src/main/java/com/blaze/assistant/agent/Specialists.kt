package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Specialists: focused sub-agents Blaze can hand a piece of work to. Each has its own prompt and
 * only the tools it needs, so it stays on task, uses fewer tokens, and several can run at once.
 */
object Specialists {
    /** [skills] = bundled skill packs it loads (v0.24.3; empty = SpecialistMatch.skills for its id). */
    data class Spec(val id: String, val name: String, val prompt: String, val groups: Set<String>, val custom: Boolean = false,
                    val skills: List<String> = emptyList())

    val builtIn = listOf(
        Spec("researcher", "Researcher", "You research on the web. Search, open the 2-4 best sources, cross-check, and return a tight answer with the key facts, numbers and source links. No filler.", setOf("web", "memory")),
        Spec("operator", "Phone operator", "You operate the Android phone through its apps to finish one task. Prefer direct tools (search_in_app, set_alarm…) over tapping. Batch steps with do_actions. Report exactly what you did and what you saw.", setOf("phone", "comms", "device")),
        Spec("writer", "Writer", "You write and edit: messages, emails, posts, summaries, scripts. Match the user's voice, keep it clear and short unless asked otherwise. Return only the finished text.", setOf("memory")),
        Spec("planner", "Planner", "You plan: break goals into steps, schedule, check the calendar and routines, and set reminders. Return a short, ordered plan and what you scheduled.", setOf("comms", "memory", "routines", "web")),
        Spec("coder", "Coder", "You write, run and debug code and shell commands in Blaze's terminal. Run things to check them; keep output short; explain fixes in one line each.", setOf("terminal", "web")),
        Spec("inbox", "Inbox manager", "You handle the user's mail, messages, notifications and connected apps: find, summarise, draft replies. Never send without approval.", setOf("connectors", "comms", "notifications", "memory")),
        // v0.24.3 (blaze-specialists): matched per turn by SpecialistMatch, each with its own skill packs.
        Spec("shopper", "Shopper", "You find and compare products for one shopping goal: real listings, prices with currency, delivery to the user's country, stock, seller rating and the link. Respect the price limit as a hard filter. Never buy or check out; return the options and your one pick with the reason.", setOf("web", "browser", "memory")),
        Spec("booker", "Booker", "You find bookings (flights, buses, hotels, tables, appointments) for one target: times, prices, availability, cancellation rules and the link. Never pay or confirm a booking; return the options that fit the limits and your pick.", setOf("web", "browser", "memory", "routines")),
        Spec("tutor", "Tutor", "You help the user learn one topic: explain simply with an example, check understanding, and turn key facts into flashcards (Learn) when asked. Never hand over answers to graded work; teach the method instead.", setOf("web", "memory", "study")),
        Spec("designer", "Designer", "You make one visual thing for real with the design tool (3D, pages, UI, posters, slides, diagrams): clear hierarchy, real content, no lorem ipsum. Return what you made and how to open it.", setOf("design", "web", "memory")),
    )

    fun custom(): List<Spec> = try {
        val a = JSONArray(Settings.str("specialists", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Spec(it.optString("id"), it.optString("name"), it.optString("prompt"),
                it.optString("groups", "web,memory").split(',').map { g -> g.trim() }.filter { g -> g.isNotEmpty() }.toSet(), true)
        }
    } catch (_: Exception) { emptyList() }

    fun all() = builtIn + custom()
    fun byId(id: String) = all().firstOrNull { it.id == id || it.name.equals(id, true) }

    fun saveCustom(list: List<Spec>) = Settings.setStr("specialists", JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt).put("groups", it.groups.joinToString(","))) }
    }.toString())

    val groupNames = listOf("web", "phone", "comms", "connectors", "memory", "routines", "terminal", "device", "notifications", "browser", "study", "design")

    private val phone = setOf("open_app", "read_screen", "look_at_screen", "tap", "type_text", "scroll", "press_button", "open_url", "dial",
        "compose_sms", "set_alarm", "set_timer", "wait", "do_actions", "search_in_app", "run_skill")
    fun group(tool: String): String = when {
        tool in Web.names -> "web"
        tool.startsWith("browser_") -> "browser"
        tool == "flashcards" -> "study"
        tool == Designs.TOOL -> "design"
        tool in phone -> "phone"
        tool in com.blaze.assistant.phone.Skills.names -> "comms"
        tool.startsWith("mcp_") || tool.startsWith("gmail") || tool.startsWith("gcal") || tool.startsWith("drive") || tool.startsWith("gcontacts")
            || tool.startsWith("github") || tool.startsWith("spotify") || tool.startsWith("notion") || tool.startsWith("slack") || tool == "share_to_app" -> "connectors"
        tool in setOf("remember", "forget", "recall_memory", "search_past_chats", "search_screen_memory") -> "memory"
        tool.endsWith("_routine") || tool == "list_routines" || tool.endsWith("_trigger") || tool == "list_triggers" -> "routines"
        tool.startsWith("terminal") || tool == "run_shell" || tool == "git" || tool in com.blaze.assistant.terminal.DevTools.names -> "terminal"
        tool == "device_status" -> "device"
        tool.contains("notification") -> "notifications"
        else -> "other"
    }

    fun filter(all: JSONArray, s: Spec): JSONArray = JSONArray().apply {
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i)
            val n = t.getJSONObject("function").getString("name")
            if (n == "delegate") continue
            if (group(n) in s.groups) put(t)
        }
    }

    fun tool(): JSONObject {
        val list = all().joinToString("; ") { "${it.id} (${it.name})" }
        return JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "delegate")
            .put("description", "Hand work to specialists: $list. Give each a clear, self-contained task with all needed details (they don't see this chat). Tasks in one call run at the same time (except phone operator ones, which take turns). Use for big work that splits into independent pieces, one task per target (compare 3 shops = 3 tasks); each task is a tight brief: the goal, what to return, limits, and to stop and report after two failed tries. Do small or step-by-step work yourself.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("tasks", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("specialist", JSONObject().put("type", "string"))
                    .put("task", JSONObject().put("type", "string")))
                    .put("required", JSONArray(listOf("specialist", "task"))))))
                .put("required", JSONArray(listOf("tasks")))))
    }
}
