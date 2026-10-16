package com.blaze.assistant.agent

/**
 * v0.28.8: proof for every action. v0.28.7 stopped fake "message sent" replies; this does the same for every
 * action Blaze can claim in a final reply: calls, alarms and timers, reminders and routines, calendar events,
 * device settings, saving to memory, files, emails, opening apps or pages, installs, downloads, contacts and
 * music. A claim needs a tool from its group that SUCCEEDED this turn ([ClaimGuard.succeeded] on the result).
 *
 * In the agent loop an unbacked claim gets one corrective re-prompt ([nudge]) and then the claim sentences are
 * replaced with an honest line ([honest]). Pure Kotlin; ActionProofTest holds the real failures and paraphrases.
 */
object ActionProof {
    data class Action(val id: String, val what: String, val claim: Regex, val tools: Set<String>, val asks: Regex)

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private fun r(s: String) = Regex(s, ic)

    // Subject words before a past-tense verb: "I've", "I just", "it's been", "done,", or the verb opening the sentence.
    private const val ME = """(?:^\s*(?:done[,!.:]?\s*|ok(?:ay)?[,!.]?\s*|sure[,!.]?\s*|all set[,!.]?\s*)?|\b(?:i|i've|i have|i just|i've just|i already|we've)\s+(?:also\s+|now\s+|just\s+|successfully\s+)?)"""

    val ACTIONS: List<Action> = listOf(
        Action("send", "send that message",
            r("""\b(?:message|text|picture|image|photo|file|reply|it|email)\s+(?:was\s+|has been\s+|is\s+)?(?:successfully\s+)?sent\b|^\s*(?:done[,!.]?\s*)?sent\b|$ME(?:sent|messaged|texted|replied|forwarded|whatsapp(?:ed|'d))\b|\bsuccessfully sent\b|\bdelivered (?:it|the message|your message)\b"""),
            setOf("message_contact", "send_sms", "send_text", "reply_to_notification", "notif_reply", "gmail_send", "send_email", "slack_post", "share_to_app"),
            r("""\b(send|message|text|whatsapp|telegram|sms|forward|tell|reply|email|dm)\b""")),
        Action("call", "place that call",
            r("""$ME(?:called|dialed|dialled|rang|phoned)\b|\b(?:calling|dialing|dialling|ringing)\s+(?!you\b)[A-Z\w]|\bcall (?:placed|started|connected)\b"""),
            setOf("call_number", "call_contact", "dial"),
            r("""\b(call|ring|phone|dial)\b""")),
        Action("alarm", "set that alarm or timer",
            r("""$ME(?:set|created|added|scheduled|started)\b[^.!?\n]{0,25}\b(?:alarm|timer)\b|\b(?:alarm|timer)\b[^.!?\n]{0,15}\b(?:is |has been )?(?:set|started|on|running)\b|\bwake you(?: up)? at\b"""),
            setOf("set_alarm", "set_timer", "create_routine", "schedule_command"),
            r("""\b(alarm|timer|wake me|countdown)\b""")),
        Action("reminder", "set that reminder",
            r("""$ME(?:set|created|added|scheduled)\b[^.!?\n]{0,25}\b(?:reminder|routine|event|meeting|appointment)\b|\b(?:reminder|routine|event)\b[^.!?\n]{0,15}\b(?:is |has been )(?:set|created|added|scheduled)\b|\bi(?:'ll| will) remind you\b"""),
            setOf("create_routine", "schedule_command", "add_calendar_event", "gcal_create", "move_event", "set_alarm", "post_notification"),
            r("""\b(remind|reminder|schedule|calendar|event|meeting|appointment|routine)\b""")),
        Action("setting", "change that setting",
            r("""$ME(?:turned|switched|toggled|enabled|disabled|put)\b[^.!?\n]{0,25}\b(?:wi-?fi|bluetooth|flashlight|torch|hotspot|airplane mode|flight mode|dark mode|do not disturb|dnd|brightness|volume|mobile data|data|location|gps|nfc|auto-?rotate|rotation|battery saver|power saving|ringer|silent|vibrate)\b|$ME(?:muted|unmuted|silenced)\b|\b(?:wi-?fi|bluetooth|flashlight|torch|hotspot|airplane mode|flight mode|dark mode|do not disturb|dnd|brightness|volume|mobile data|data|location|gps|nfc|auto-?rotate|rotation|battery saver|power saving|ringer|silent|vibrate)\b[^.!?\n]{0,20}\b(?:is now|now)\s+(?:on|off|enabled|disabled|up|down|at)\b|$ME(?:set|raised|lowered|turned up|turned down)\b[^.!?\n]{0,15}\b(?:volume|brightness)\b"""),
            setOf("device_setting", "set_volume", "system_action", "phone_mode", "open_settings_page", "tap", "press_button", "media_control"),
            r("""\b(turn|switch|toggle|enable|disable|mute|unmute|wi-?fi|bluetooth|flashlight|torch|hotspot|airplane|dark mode|brightness|volume|do not disturb|dnd)\b""")),
        Action("memory", "save that to memory",
            r("""$ME(?:saved|noted|stored|added|put)\b[^.!?\n]{0,30}\bmemor(?:y|ies)\b|$ME(?:remembered|memori[sz]ed)\b|\bi(?:'ll| will) remember\b|\b(?:saved|added) (?:it |that )?to (?:my |your )?memory\b|\bgot it,? i(?:'ll| will) remember\b"""),
            setOf("memory_save", "memory_update", "remember", "update_brief"),
            r("""\b(remember|memori[sz]e|save|note|don't forget|keep in mind|memory)\b""")),
        Action("file", "write that file",
            r("""$ME(?:saved|created|wrote|written|exported|deleted|renamed|moved)\b[^.!?\n]{0,30}\b(?:file|pdf|document|doc|csv|spreadsheet|folder|download)s?\b|\b(?:file|pdf|document)\b[^.!?\n]{0,15}\b(?:is |has been )(?:saved|created|exported|ready)\b"""),
            setOf("file_write", "file_edit", "file_delete", "export_file", "run_code", "run_shell", "download_file", "gallery", "termux_run"),
            r("""\b(file|pdf|document|doc|csv|spreadsheet|export|save|folder|delete)\b""")),
        Action("open", "open that",
            r("""$ME(?:opened|launched|started|loaded)\b[^.!?\n]{0,30}\b(?:app|whatsapp|youtube|spotify|camera|settings|browser|page|site|website|link|maps|chrome|gallery|instagram|tiktok|facebook)\b|\b(?:whatsapp|youtube|spotify|camera|settings|maps|chrome)\b[^.!?\n]{0,10}\bis (?:now )?open\b"""),
            setOf("open_app", "open_url", "browser_open", "browser_show", "open_camera", "blaze_camera", "open_settings_page", "open_settings", "navigate_to", "search_in_app", "share_to_app", "spotify_play", "message_contact", "tap", "browser_read", "fetch_page", "browser_click"),
            r("""\b(open|launch|start|go to|show me|play)\b""")),
        Action("install", "install or uninstall that",
            r("""$ME(?:installed|uninstalled|removed|updated)\b[^.!?\n]{0,30}\b(?:app|apk|package|skill|linux|distro|ubuntu|debian|alpine|python|node)\b"""),
            setOf("github_install_apk", "linux_install", "install_skill", "uninstall_app", "run_shell", "termux_run", "skill_manage"),
            r("""\b(install|uninstall|remove|update|download)\b""")),
        Action("download", "download that",
            r("""$ME(?:downloaded)\b|\b(?:download|file)\b[^.!?\n]{0,15}\b(?:is |has )(?:finished|complete|done|been saved)\b"""),
            setOf("download_file", "run_shell", "http_request", "browser_click", "termux_run"),
            r("""\b(download|save|grab|get)\b""")),
        Action("contact", "change that contact",
            r("""$ME(?:added|saved|created|updated|edited|deleted)\b[^.!?\n]{0,25}\b(?:contact|number)\b"""),
            setOf("add_contact", "edit_contact"),
            r("""\b(contact|number|save .* as)\b""")),
        Action("music", "play that",
            r("""$ME(?:played|started playing|queued|paused|skipped|resumed)\b|\b(?:now playing|is playing|playing now)\b"""),
            setOf("spotify_play", "spotify_control", "spotify_now_playing", "media_control", "open_url", "open_app", "search_in_app"),
            r("""\b(play|pause|skip|next song|music|song|spotify|resume)\b""")),
    )

    private val negation = r("""\b(not|never|couldn't|could not|didn't|did not|wasn't|haven't|hasn't|unable|failed|can't|cannot|won't|once|after|if|until|want me|shall i|do you want)\b|\?\s*$""")
    private val pastTime = r("""\b(earlier|yesterday|last time|previously|before|already did|this morning|last week)\b""")

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The sentences of [reply] that claim [a] happened now. */
    fun claims(reply: String, a: Action): List<String> = sentences(reply).filter { s ->
        a.claim.containsMatchIn(s) && !negation.containsMatchIn(s) && !pastTime.containsMatchIn(s)
    }

    /** Is the user's message a request that could involve [a] (not a question about something done before)? */
    fun asked(userText: String, a: Action): Boolean =
        a.asks.containsMatchIn(userText) && !r("""^\s*(did|have|has|was|were|why did|why didn't)\b""").containsMatchIn(userText)

    /** The first action [reply] claims with no succeeded tool of its group this turn, or null. */
    fun unbacked(reply: String, okTools: Collection<String>, userText: String): Action? {
        if (reply.isBlank()) return null
        for (a in ACTIONS) {
            if (!asked(userText, a)) continue
            if (claims(reply, a).isEmpty()) continue
            if (okTools.none { it in a.tools }) return a
        }
        return null
    }

    /** The one re-prompt: do it with a tool now, or say plainly it isn't done. */
    fun nudge(a: Action): String = "[Blaze check] Your reply says you did this (${a.what}), but no tool that does it succeeded this turn, " +
        "so it has NOT happened. Do it now with the right tool (${a.tools.take(5).joinToString(" / ")}), wait for its result, and only then reply. " +
        "If the tool fails or you can't, say plainly that it wasn't done and why. Never say it was done without a successful tool result."

    /** The claim sentences swapped for the truth (the rest of the reply is kept). */
    fun honest(reply: String, a: Action): String {
        val bad = claims(reply, a).toSet()
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        val line = "I didn't actually ${a.what}: nothing did it this time. Say \u201ctry again\u201d and I'll do it properly."
        return if (rest.length < 12) line else "$line\n\n$rest"
    }
}
