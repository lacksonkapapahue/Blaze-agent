package com.blaze.assistant.agent

/**
 * Views first (v0.23.0). Luke's rule: Blaze always uses its views. Output that fits a view (a deck of
 * flashcards, a page, a reminder, a picture, a memory…) is made THERE, the view is opened or linked,
 * and the chat gets a short answer, never the whole thing dumped as text.
 *
 *  - [PROMPT]: the always-present VIEWS block of the system prompt (each view, what belongs there,
 *    the tool that writes to it, and the hard rule).
 *  - [route]: a cheap keyword/intent classifier (no model call) that picks the view a request needs.
 *    The agent loop turns it into a required-view hint ([hint]) and switches on that view's tools ([Spec.groups]).
 *  - [cardsFrom] / [deckName] / [shouldConvert]: the safety net for Learn. When the model still writes
 *    flashcards or a quiz as text without calling the flashcards tool, the agent turns that text into
 *    a Learn deck (or re-prompts once when nothing can be parsed).
 * Pure Kotlin (no Android), unit-tested in ViewRouterTest.
 */
object ViewRouter {

    /** A view the agent writes to. [tools] = the tools that put things there; [groups] = ToolRouter groups to switch on. */
    data class Spec(val id: String, val name: String, val holds: String, val tools: List<String>, val groups: List<String>, val cue: Regex)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** In priority order: the first that matches is the required view. */
    val specs = listOf(
        Spec("learn", "Learn", "flashcards, decks, quizzes and anything to study or memorise (spaced repetition)",
            listOf("flashcards", "open_view"), listOf("study"),
            r("""\b(flash ?cards?|quiz(?:zes)?|test me|(?<!case )study|studying|revise|revision|memori[sz]e|spaced repetition|exam prep|cards? (?:on|about|for)|(?<!slide )(?<!pitch )deck)\b""")),
        Spec("schedule", "Schedule", "reminders, routines, repeating tasks, timed jobs and triggers",
            listOf("create_routine", "schedule_command", "create_trigger", "set_alarm", "add_calendar_event", "open_view"), listOf("schedule", "device"),
            r("""\b(remind(?:er|ers)? ?(?:me)?|every (?:day|morning|evening|night|week|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|hour|\d+)|daily|weekly|routine|schedule (?:a|this|it|my)|wake me)\b""")),
        Spec("design", "Design", "web pages, sites, landing pages, posters, slides and other visual layouts (live preview)",
            listOf("file_write", "open_view"), listOf("terminal", "media"),
            r("""\b(website|web ?site|web ?page|landing page|html|css|poster|flyer|mock-?up|wireframe|portfolio|slides?|slide deck|presentation|design (?:a|an|me|my|the|this))\b""")),
        Spec("gallery", "Gallery", "images, pictures, logos, wallpapers, beats, music and other media Blaze makes",
            listOf("generate_image", "gallery"), listOf("media"),
            r("""\b(?:(?:make|generate|draw|create|paint|produce)\b.{0,40}\b(?:image|picture|pic|photo|logo|icon|wallpaper|art|artwork|illustration|beat|beats|song|instrumental|sticker|avatar)s?|my gallery|in (?:the|my) gallery)\b""")),
        Spec("skills", "Skills", "saved methods and workflows (skills), installing skill packs",
            listOf("write_skill", "install_skill", "use_skill"), listOf("extras"),
            r("""\b((?:make|save|create|write|install|add)\b.{0,30}\bskills?|skill library|my skills|as a skill)\b""")),
        Spec("projects", "Projects", "ongoing efforts with their own threads, brief and instructions",
            listOf("update_brief", "open_view"), emptyList(),
            r("""\b((?:new|start|create|open) (?:a )?project|my projects|project (?:for|about|called))\b""")),
        Spec("archive", "Archive", "old chats from before one mind (read-only) and searching past conversations",
            listOf("search_past_chats", "search_chat"), emptyList(),
            r("""\b((?:old|past|previous|earlier|archived) (?:chats?|conversations?)|chat history|what did (?:we|i) (?:say|talk|discuss)|we talked about|last time we)\b""")),
        Spec("memory", "Memory", "lasting facts about the user, their people, preferences and decisions",
            listOf("memory_save", "remember", "forget", "memory_search"), emptyList(),
            r("""\b(remember (?:that|this|my|me)|don'?t forget (?:that|my)|forget (?:that|my|about)|what do you (?:know|remember)|keep in mind)\b""")),
        Spec("browser", "Browser", "websites Blaze opens, fills in or signs in to (background tabs; browser_show only when the user must step in)",
            listOf("browser_open", "browser_click", "browser_type", "browser_secure_input"), listOf("browser"),
            r("""(\b(?:open|go to|visit|log ?in to|sign ?in to|sign up (?:on|for)|fill (?:in|out))\b.{0,40}(?:\bsite\b|\bwebsite\b|\bportal\b|\.com\b|\.org\b|\.zm\b|https?://)|https?://)""")),
    )

    fun byId(id: String): Spec? = specs.firstOrNull { it.id == id }

    /** The view a request needs, or null (plain chat). Slash commands and routines are left alone. */
    fun route(text: String): Spec? {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/") || t.startsWith("[Scheduled routine")) return null
        return specs.firstOrNull { it.cue.containsMatchIn(t) }
    }

    /** All views a request touches, first = required. */
    fun routeAll(text: String): List<Spec> = if (route(text) == null) emptyList() else specs.filter { it.cue.containsMatchIn(text) }

    val PROMPT: String = buildString {
        append("\n\nVIEWS (always on; you use them without being asked). Blaze has views, screens the user can open from /views. ")
        append("HARD RULE: output that fits a view is CREATED in that view with its tool, then the view is opened (open_view) or named so the user can tap it, ")
        append("and the chat reply is one or two short lines (what you made, where). Never dump it as text in chat instead. ")
        append("You don't need the user to say \"use the view\": every turn, pick the view that fits.\n")
        for (s in specs) append("- ${s.name} (/${s.id}): ${s.holds}. Write with ${s.tools.filter { it != "open_view" }.ifEmpty { listOf("open_view") }.joinToString(" / ")}.\n")
        append("- Flashcards, \"quiz me\", \"test me\", study or revision → Learn: flashcards action=add (deck, cards=[{front, back}]), then open_view view=learn. Never write Q: / A: lists in chat.\n")
        append("- Pages and visual work → Design: write the file, then open_view view=design target=<path or URL>, without asking.\n")
        append("- \"Remind me\" or anything repeating → Schedule (create_routine / schedule_command / create_trigger). Pictures and beats → Gallery. A method to reuse → Skills (write_skill).\n")
        append("- Other views (Jobs, Accounts, Agents, Usage, Settings…): open with open_view ask=true unless the user asked for that view.")
    }

    /** The per-turn required-view hint, injected before the first model call. */
    fun hint(s: Spec): String = when (s.id) {
        "learn" -> "\nREQUIRED VIEW THIS TURN: Learn. Put the cards in a Learn deck with flashcards action=add (deck = topic or course code + topic, " +
            "cards = [{front, back}], 10-25 cards, one fact each), then open_view view=learn, then reply in one or two lines (deck name, card count). " +
            "Do NOT write the questions and answers in chat. To quiz from an existing deck: flashcards action=due and ask one card at a time."
        "design" -> "\nREQUIRED VIEW THIS TURN: Design. Build the page as a file (file_write), then open_view view=design target=<the file or URL>; reply briefly. Don't paste the HTML in chat."
        "schedule" -> "\nREQUIRED VIEW THIS TURN: Schedule. Create it with create_routine / schedule_command / create_trigger (or set_alarm / add_calendar_event for one-off times) and say when it will run in one line."
        "gallery" -> "\nREQUIRED VIEW THIS TURN: Gallery. Make it with generate_image (art/photos) or run_code (beats, charts) saved under /root/phone/ so it lands in the Gallery; reply briefly."
        "skills" -> "\nREQUIRED VIEW THIS TURN: Skills. Save or install it with write_skill / install_skill; say its name in one line."
        "projects" -> "\nREQUIRED VIEW THIS TURN: Projects. Use open_view view=projects so the user can create or open the project there; keep the reply short."
        "archive" -> "\nREQUIRED VIEW THIS TURN: Archive. Look it up with search_past_chats / search_chat and answer from what you find; offer open_view view=chats for the full thread."
        "memory" -> "\nREQUIRED VIEW THIS TURN: Memory. Save, update or forget with memory_save / remember / forget (or look up with memory_search); confirm in one line."
        "browser" -> "\nREQUIRED VIEW THIS TURN: Browser. Do it in Blaze's background browser (browser_open, browser_click, browser_type, browser_secure_input); browser_show only if the user must step in."
        else -> "\nREQUIRED VIEW THIS TURN: ${s.name}. Create the result there with ${s.tools.joinToString(" / ")}, open or link it, and reply briefly."
    }

    // ---------------- Learn safety net ----------------

    /** The user asked for cards themselves (not just "help me study"), so a reply with no deck gets one re-prompt. */
    private val wantsCards = r("""\b(flash ?cards?|cards? (?:on|about|for|from)|(?:a |new )?deck|quiz(?:zes)?|test me)\b""")
    fun asksForCards(userText: String) = wantsCards.containsMatchIn(userText)

    private val strip = Regex("""^\s*(?:[-*•>]\s+|\d{1,3}[.)]\s+|#{1,6}\s*)*""")
    private fun clean(s: String) = s.replace("**", "").replace("__", "").replace(Regex("""(?<!\w)\*(?!\s)|(?<!\s)\*(?!\w)"""), "").replace("`", "").trim().trim('|').trim()

    private val qRe = Regex("""^(?:q(?:uestion)?|front|term|card\s*\d+\s*(?:front)?)\s*\d*\s*[:.\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val aRe = Regex("""^(?:a(?:nswer|ns)?|back|definition|def)\s*\d*\s*[:.\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val dashRe = Regex("""^(.{2,80}?)\s+[–—-]\s+(.{2,})$""")

    /**
     * Flashcard / quiz pairs written as text: "Q: … / A: …" (also Question/Answer, Front/Back, Term/Definition,
     * numbered and bold), two-column markdown tables, and "**Term** – definition" lines. Empty when it isn't cards.
     */
    fun cardsFrom(text: String): List<Pair<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>()
        // 1) Q / A pairs (the answer may be on the same line after the question: "Q: x A: y").
        var pendingQ: String? = null
        for (raw in lines) {
            val l = clean(raw.replace(strip, ""))
            val inline = Regex("""^(?:q(?:uestion)?|front)\s*\d*\s*[:.]\s*(.+?)\s+(?:a(?:nswer)?|back)\s*[:.]\s*(.+)$""", RegexOption.IGNORE_CASE).find(l)
            if (inline != null) { out += inline.groupValues[1].trim() to inline.groupValues[2].trim(); pendingQ = null; continue }
            val q = qRe.find(l); val a = aRe.find(l)
            when {
                q != null && a == null -> pendingQ = q.groupValues[1].trim()
                a != null && pendingQ != null -> { out += pendingQ!! to a.groupValues[1].trim(); pendingQ = null }
            }
        }
        if (out.size >= 2) return out.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        out.clear()
        // 2) A two-column table: | front | back |
        val rows = lines.filter { it.startsWith("|") && it.count { c -> c == '|' } == 3 }
        if (rows.size >= 3) {
            val body = rows.filterNot { it.replace(Regex("[|:\\-\\s]"), "").isEmpty() }
            val cells = body.map { it.trim('|').split('|').map { c -> clean(c) } }
            val data = if (cells.isNotEmpty() && cells[0].any { it.lowercase() in setOf("front", "question", "term", "q", "back", "answer", "definition", "a") }) cells.drop(1) else cells
            data.filter { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }.forEach { out += it[0] to it[1] }
            if (out.size >= 2) return out
            out.clear()
        }
        // 3) "**Term** – definition" (only bold terms, so ordinary sentences with dashes don't count)
        for (raw in lines) {
            val l = raw.replace(strip, "")
            if (!l.startsWith("**")) continue
            val m = dashRe.find(l) ?: Regex("""^\*\*(.{2,80}?)\*\*\s*[:–—-]\s*(.{2,})$""").find(l) ?: continue
            out += clean(m.groupValues[1]).trimEnd(':') to clean(m.groupValues[2])
        }
        return if (out.size >= 3) out else emptyList()
    }

    /** "make flashcards on the French revolution" → "The French revolution". */
    fun deckName(userText: String): String {
        val m = Regex("""\b(?:on|about|for|from|of|covering)\s+(?:the\s+topic\s+)?(.{2,60}?)(?:[.?!,]|\s+(?:please|for me|so|and quiz|then)\b|$)""", RegexOption.IGNORE_CASE)
            .find(userText.substringAfter(Regex("""(?i)flash ?cards?|quiz(?:zes)?|deck|test me|cards""").find(userText)?.value ?: "", userText))
        val raw = m?.groupValues?.get(1)?.trim()?.trim('"', '\'', '“', '”')?.removePrefix("my ")?.trim().orEmpty()
        return raw.replaceFirstChar { it.uppercase() }.take(40).ifBlank { "Cards" }
    }

    /** What the agent does with a final text reply on a Learn turn that never called the flashcards tool. */
    enum class Fix { NONE, CONVERT, REPROMPT }

    fun shouldConvert(userText: String, reply: String, calledFlashcards: Boolean, alreadyNudged: Boolean): Fix {
        if (calledFlashcards) return Fix.NONE
        if (route(userText)?.id != "learn") return Fix.NONE
        val cards = cardsFrom(reply)
        if (cards.size >= 2) return Fix.CONVERT
        // One quiz question at a time (interactive) is fine; a refusal or plain explanation of "make cards" is not.
        if (asksForCards(userText) && !alreadyNudged && !Regex("""(?i)\bquiz me\b|\btest me\b""").containsMatchIn(userText)) return Fix.REPROMPT
        return Fix.NONE
    }

    const val NUDGE = "You answered in chat, but flashcards belong in the Learn view (the user's standing rule). " +
        "Call flashcards action=add now with deck and cards=[{front, back}], then open_view view=learn, then reply in one or two lines. Don't list the cards in chat."

    fun converted(deck: String, added: Int, total: Int): String =
        "Made the **$deck** deck in Learn: $added card${if (added == 1) "" else "s"}" +
            (if (added < total) " (${total - added} were already there)" else "") +
            ". It's open now; cards come back right before you'd forget them (/learn)."
}
