package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * Plain-language rules for the everyday commands every assistant should nail, with no model at all:
 * alarms, timers, torch, Wi-Fi/Bluetooth, volume, media keys, opening an app. Works offline.
 * Returns a fast-lane tool call (name + arguments) or null.
 */
object QuickRules {
    private fun clean(s: String) = s.trim().lowercase()
        .replace(Regex("[?!.,]+$"), "").replace(Regex("\\s+"), " ")
        .replace(Regex("^(hey blaze|ok blaze|okay blaze|blaze)[, ]+"), "")
        .replace(Regex("^(please|can you|could you|would you|will you|i want you to|i need you to)[, ]+"), "")
        .replace(Regex("^(please)[, ]+"), "")
        .replace(Regex("( for me| please| now| blaze)+$"), "").trim()

    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty five" to 45, "sixty" to 60, "a" to 1, "an" to 1, "half an" to 0)

    private fun num(s: String): Int? = s.toIntOrNull() ?: words[s]

    fun parse(text: String): Pair<String, JSONObject>? {
        val t = clean(text)
        if (t.isEmpty() || t.length > 70) return null

        // Timer: "set a timer for 5 minutes", "timer 10 min", "5 minute timer", "half an hour timer"
        Regex("""^(?:set |start )?(?:a |an )?(?:timer|countdown)(?: for)? (.+)$|^(.+?) (?:timer|countdown)$""").matchEntire(t)?.let { m ->
            val d = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            duration(d)?.let { s -> return "set_timer" to JSONObject().put("minutes", s / 60).put("seconds", s % 60) }
        }

        // Alarm: "set an alarm for 6:30 am", "wake me up at 7", "alarm 5:45 pm"
        Regex("""^(?:set |make |create )?(?:an |a |my )?(?:alarm|wake ?up)(?: call)?(?: for| at)? (.+)$|^wake me(?: up)?(?: at| by)? (.+)$""").matchEntire(t)?.let { m ->
            val d = m.groupValues[1].ifBlank { m.groupValues[2] }.trim().removeSuffix(" tomorrow").removeSuffix(" tomorrow morning")
            clock(d)?.let { (h, mi) -> return "set_alarm" to JSONObject().put("hour", h).put("minute", mi) }
        }

        // Torch / Wi-Fi / Bluetooth / DND / airplane
        val settings = listOf("flashlight" to "flashlight|torch|flash ?light|light", "wifi" to "wi-?fi|wireless",
            "bluetooth" to "bluetooth", "do_not_disturb" to "do not disturb|dnd", "airplane" to "airplane mode|flight mode|aeroplane mode",
            "mobile_data" to "mobile data|data")
        for ((id, pat) in settings) {
            Regex("""^(?:turn|switch|put) (on|off) (?:the |my )?(?:$pat)$|^(?:turn|switch|put) (?:the |my )?(?:$pat) (on|off)$|^(?:the |my )?(?:$pat) (on|off)$|^(enable|disable|activate|deactivate) (?:the |my )?(?:$pat)$""")
                .matchEntire(t)?.let { m ->
                    val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@let
                    val on = v in setOf("on", "enable", "activate")
                    return "toggle_setting" to JSONObject().put("setting", id).put("on", on)
                }
        }

        // Volume
        when {
            Regex("""^(?:turn |put )?(?:the )?(?:volume|sound) up$|^(?:louder|increase (?:the )?volume|raise (?:the )?volume)$""").matches(t) -> return "set_volume" to JSONObject().put("level", "up")
            Regex("""^(?:turn |put )?(?:the )?(?:volume|sound) down$|^(?:quieter|softer|lower (?:the )?volume|decrease (?:the )?volume|reduce (?:the )?volume)$""").matches(t) -> return "set_volume" to JSONObject().put("level", "down")
            Regex("""^(?:mute|silence)(?: the)?(?: phone| volume| sound| my phone)?$|^(?:turn )?(?:the )?(?:volume|sound) off$""").matches(t) -> return "set_volume" to JSONObject().put("level", "mute")
            Regex("""^(?:max|maximum|full) volume$|^(?:turn |put )?(?:the )?volume (?:to )?(?:max|maximum|full)$""").matches(t) -> return "set_volume" to JSONObject().put("level", "max")
        }

        // Media keys
        when (t) {
            "pause", "pause the music", "pause music", "pause it", "stop the music", "stop music" -> return "media_control" to JSONObject().put("action", "pause")
            "resume", "resume music", "resume the music", "play", "continue playing", "unpause" -> return "media_control" to JSONObject().put("action", "play")
            "next", "next song", "next track", "skip", "skip this song", "skip song", "skip track" -> return "media_control" to JSONObject().put("action", "next")
            "previous", "previous song", "previous track", "last song", "go back a song" -> return "media_control" to JSONObject().put("action", "previous")
        }

        // Open an app: "open whatsapp", "launch the camera", "go to settings". Single target only.
        Regex("""^(?:open|launch|start|go to|take me to|bring up|show me|run) (?:the |my |up )?([a-z0-9][a-z0-9 .+&'-]{1,28}?)(?: app)?$""").matchEntire(t)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.split(' ').size <= 3 && !Regex("\\b(and|then|search|play|on|in|with|to|for|about)\\b").containsMatchIn(app))
                return "open_app" to JSONObject().put("name", app)
        }
        return null
    }

    /** "5 minutes", "1 hour 30 minutes", "90 seconds", "half an hour", "an hour and a half". */
    fun duration(d: String): Int? {
        val s = d.trim().removePrefix("for ")
        if (Regex("^half an? hour$").matches(s)) return 1800
        if (Regex("^an? hour and a half$|^one and a half hours?$|^1\\.5 hours?$").matches(s)) return 5400
        var total = 0; var any = false
        Regex("""(\d+(?:\.\d+)?|[a-z]+(?: [a-z]+)?) ?(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""").findAll(s).forEach { m ->
            val raw = m.groupValues[1]
            val n = raw.toDoubleOrNull() ?: num(raw)?.toDouble() ?: num(raw.substringAfterLast(' '))?.toDouble() ?: return@forEach
            val u = m.groupValues[2]
            total += when { u.startsWith("h") -> (n * 3600).toInt(); u.startsWith("m") -> (n * 60).toInt(); else -> n.toInt() }
            any = true
        }
        return if (any && total in 1..86_400) total else null
    }

    /** "6", "6:30", "6.30", "6 30 am", "18:45", "7 pm", "noon", "midnight", "half past 6". */
    fun clock(d: String): Pair<Int, Int>? {
        val s = d.trim().replace("a.m.", "am").replace("p.m.", "pm").replace(" o'clock", "").replace(" oclock", "")
        if (s == "noon" || s == "midday") return 12 to 0
        if (s == "midnight") return 0 to 0
        Regex("""^half past (\d{1,2})(?: ?(am|pm))?(?: in the (morning|evening|afternoon|night))?$""").matchEntire(s)?.let { m ->
            return fix(m.groupValues[1].toInt(), 30, m.groupValues[2], m.groupValues[3]) }
        Regex("""^(\d{1,2})(?:[:. ](\d{2}))? ?(am|pm)?(?: in the (morning|evening|afternoon|night)| tonight)?$""").matchEntire(s)?.let { m ->
            val pm = m.groupValues[3].ifBlank { if (s.endsWith("tonight")) "pm" else "" }
            return fix(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, pm, m.groupValues[4]) }
        return null
    }

    private fun fix(h0: Int, m: Int, ampm: String, part: String): Pair<Int, Int>? {
        var h = h0
        if (h > 23 || m > 59) return null
        val pm = ampm == "pm" || part in setOf("evening", "afternoon", "night")
        val am = ampm == "am" || part == "morning"
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        return h to m
    }
}
